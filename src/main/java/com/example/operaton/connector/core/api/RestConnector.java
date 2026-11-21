package com.example.operaton.connector.core.api;

import com.example.operaton.connector.core.error.ConnectorError;
import com.example.operaton.connector.core.error.RestConnectorException;

/**
 * The connector use case: build a request from the call's sources and schemas, send it, check the
 * response. Independent of the workflow engine; the engine adapter
 * ({@code connector.engine.RestConnectorDelegate}) reads a {@link RestCall} from the task and
 * stores the {@link RestResult}.
 */
public interface RestConnector {

  /**
   * @throws ConnectorError          a business error the process can model (HTTP 4xx, schema violations,
   *                                 and 5xx / connection problems with {@link TechnicalErrors#BPMN_ERROR})
   * @throws RestConnectorException  a technical problem or a modelling mistake (incident / retry)
   */
  RestResult execute(RestCall call, CallContext context);
}
