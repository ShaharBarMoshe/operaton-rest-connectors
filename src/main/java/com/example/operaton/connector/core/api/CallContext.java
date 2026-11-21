package com.example.operaton.connector.core.api;

import java.util.Optional;

/** What a call may look up while it is built; the engine adapter backs it with the execution. */
public interface CallContext {

  /** Evaluates an expression written in a schema as {@code x-expression} (wrapped in ${ }). */
  Object evaluate(String expression);

  /** A variable, used for parameters the sources do not contain. */
  Optional<Object> variable(String name);

  /** Where the call happens (the activity id), for messages. */
  String location();
}
