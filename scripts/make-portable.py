#!/usr/bin/env python3
"""
Make BPMN models editable in any Modeler: Camunda Modeler, the Miragon VS Code BPMN Modeler,
Operaton Modeler, plain bpmn.io - with or without the REST connector templates.

The models use the Camunda 7 namespace (camunda:inputOutput, camunda:delegateExpression, ...),
which every one of those Modelers shows and the Operaton engine runs. A file saved in the
Operaton namespace (Operaton Modeler does that) is converted back first.

A task linked to a missing element template shows only "Template not found" in the
properties panel. For every REST connector service task this script also:
  1. removes the template link (camunda:modelerTemplate / modelerTemplateVersion)
  2. adds every connector input parameter (empty when unused) in template order,
     so the field names are visible in the plain "Inputs" list
  3. writes a task documentation that explains each input (shown in any modeler)

The connector templates (.camunda/element-templates/rest-connectors.json) are the
single source of truth for input names and descriptions.

Usage:  scripts/make-portable.py [--check | --link] [file.bpmn ...]
        (default: src/main/resources/bpmn/*.bpmn)
  --check  only report, exit 1 if changes are needed
  --link   the reverse, for editing in a Modeler that HAS the templates: re-link every REST task
           to its template without touching its values (applying a template by hand in the
           Modeler would reset the task's inputs to the template defaults)
"""
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEMPLATES = os.path.join(ROOT, ".camunda", "element-templates", "rest-connectors.json")

BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL"
OPERATON = "http://operaton.org/schema/1.0/bpmn"
CAMUNDA = "http://camunda.org/schema/1.0/bpmn"
MODELER = "http://camunda.org/schema/modeler/1.0"
# tells Camunda Modeler / the Miragon VS Code modeler that this is a Camunda 7 diagram
PLATFORM = {f"{{{MODELER}}}executionPlatform": "Camunda Platform",
            f"{{{MODELER}}}executionPlatformVersion": "7.24.0"}
NS = {
    "bpmn": BPMN, "bpmndi": "http://www.omg.org/spec/BPMN/20100524/DI",
    "dc": "http://www.omg.org/spec/DD/20100524/DC", "di": "http://www.omg.org/spec/DD/20100524/DI",
    "camunda": CAMUNDA, "modeler": MODELER, "operaton": OPERATON, "xsi": "http://www.w3.org/2001/XMLSchema-instance",
}
for prefix, uri in NS.items():
    ET.register_namespace(prefix, uri)

DOC_MARKER = "[REST connector]"
A_TEMPLATE = f"{{{CAMUNDA}}}modelerTemplate"
A_TEMPLATE_VERSION = f"{{{CAMUNDA}}}modelerTemplateVersion"
A_DELEGATE = f"{{{CAMUNDA}}}delegateExpression"


def load_templates():
    with open(TEMPLATES, encoding="utf-8") as f:
        templates = json.load(f)
    by_id, by_delegate = {}, {}
    for t in templates:
        delegate = next(p["value"] for p in t["properties"]
                        if p["binding"].get("name") == "camunda:delegateExpression")
        inputs = [(p["binding"]["name"], p.get("label", ""), p.get("description", ""))
                  for p in t["properties"] if p["binding"]["type"] == "camunda:inputParameter"]
        info = {"id": t["id"], "version": t.get("version", 1), "name": t["name"], "delegate": delegate, "inputs": inputs,
                "description": t.get("description", "")}
        by_id[t["id"]] = info
        by_delegate[delegate] = info
    return by_id, by_delegate


def documentation(info):
    lines = [f"{DOC_MARKER} {info['name']} - {info['description']}",
             f"Implementation: delegateExpression {info['delegate']}",
             "Inputs (leave empty when not needed):"]
    lines += [f"- {name}: {desc}" for name, _label, desc in info["inputs"]]
    return "\n".join(lines)


def make_portable(task, info):
    changed = False
    for attr in (A_TEMPLATE, A_TEMPLATE_VERSION):
        if attr in task.attrib:
            del task.attrib[attr]
            changed = True

    # documentation must be the first child of a flow element
    doc = task.find("bpmn:documentation", NS)
    text = documentation(info)
    if doc is None:
        doc = ET.Element(f"{{{BPMN}}}documentation")
        task.insert(0, doc)
    if doc.text is None or doc.text.startswith(DOC_MARKER):
        if doc.text != text:
            doc.text = text
            changed = True

    ext = task.find("bpmn:extensionElements", NS)
    if ext is None:
        ext = ET.Element(f"{{{BPMN}}}extensionElements")
        task.insert(list(task).index(doc) + 1, ext)
        changed = True
    io = ext.find("camunda:inputOutput", NS)
    if io is None:
        io = ET.SubElement(ext, f"{{{CAMUNDA}}}inputOutput")
        changed = True

    inputs = {p.get("name"): p for p in io.findall("camunda:inputParameter", NS)}
    ordered = []
    for name, _label, _desc in info["inputs"]:
        param = inputs.pop(name, None)
        if param is None:
            param = ET.Element(f"{{{CAMUNDA}}}inputParameter", {"name": name})
            changed = True
        ordered.append(param)
    ordered += list(inputs.values())                     # unknown inputs are kept, after ours
    outputs = io.findall("camunda:outputParameter", NS)
    if [c for c in io] != ordered + outputs:
        changed = True
    for child in list(io):
        io.remove(child)
    for child in ordered + outputs:
        io.append(child)
    return changed


def link(task, info):
    want = {A_TEMPLATE: info["id"], A_TEMPLATE_VERSION: str(info["version"])}
    if all(task.get(k) == v for k, v in want.items()):
        return False
    task.attrib.update(want)
    return True


def to_camunda(root):
    """operaton:* -> camunda:* (same elements, other namespace) and set the execution platform."""
    old, changed = f"{{{OPERATON}}}", False
    for el in root.iter():
        if isinstance(el.tag, str) and el.tag.startswith(old):
            el.tag = f"{{{CAMUNDA}}}{el.tag[len(old):]}"
            changed = True
        for key in [k for k in el.attrib if k.startswith(old)]:
            el.attrib[f"{{{CAMUNDA}}}{key[len(old):]}"] = el.attrib.pop(key)
            changed = True
    for key, value in PLATFORM.items():
        if root.get(key) is None:
            root.set(key, value)
            changed = True
    return changed


def process(path, by_id, by_delegate, check, relink=False):
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    tree = ET.parse(path, parser)
    changed_tasks = ["namespace"] if to_camunda(tree.getroot()) else []
    for task in tree.getroot().iter(f"{{{BPMN}}}serviceTask"):
        info = by_id.get(task.get(A_TEMPLATE)) or by_delegate.get(task.get(A_DELEGATE))
        if info and (link(task, info) if relink else make_portable(task, info)):
            changed_tasks.append(task.get("id"))
    if changed_tasks and not check:
        ET.indent(tree, space="  ")
        tree.write(path, encoding="UTF-8", xml_declaration=True)
    return changed_tasks


def main(argv):
    check = "--check" in argv
    relink = "--link" in argv
    files = [a for a in argv if not a.startswith("--")] or sorted(
        glob.glob(os.path.join(ROOT, "src", "main", "resources", "bpmn", "*.bpmn")))
    by_id, by_delegate = load_templates()
    dirty = False
    for path in files:
        tasks = process(path, by_id, by_delegate, check, relink)
        if tasks:
            dirty = True
            verb = "needs changes" if check else ("linked" if relink else "updated")
            print(f"{os.path.relpath(path, ROOT)}: {verb}: {', '.join(tasks)}")
        else:
            print(f"{os.path.relpath(path, ROOT)}: ok")
    return 1 if (check and dirty) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
