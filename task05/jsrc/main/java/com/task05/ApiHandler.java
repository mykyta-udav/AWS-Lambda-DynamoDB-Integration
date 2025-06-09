package com.task05;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.*;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.model.RetentionSetting;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.*;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@LambdaHandler(
		lambdaName       = "api_handler",
		roleName         = "api_handler-role",
		isPublishVersion = true,
		aliasName        = "${lambdas_alias_name}",
		logsExpiration   = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@EnvironmentVariables({
		@EnvironmentVariable(key = "table_name", value = "${target_table}"),
		@EnvironmentVariable(key = "region",     value = "${region}")
})
public class ApiHandler
		implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	private final String tableName = System.getenv("table_name");
	private final Region region    = Region.of(System.getenv("region"));
	private final DynamoDbClient ddb =
			DynamoDbClient.builder().region(region).build();
	private final ObjectMapper om = new ObjectMapper();

	@Override
	public APIGatewayProxyResponseEvent handleRequest(
			APIGatewayProxyRequestEvent req,
			Context ctx
	) {
		try {
			ctx.getLogger().log("REQ BODY → " + req.getBody());

			EventRequest er = om.readValue(req.getBody(), EventRequest.class);

			String id        = UUID.randomUUID().toString();
			String createdAt = Instant.now().toString();
			Map<String,AttributeValue> item = new LinkedHashMap<>();
			item.put("id",         AttributeValue.builder().s(id).build());
			item.put("principalId",AttributeValue.builder().n(""+er.getPrincipalId()).build());
			item.put("createdAt",  AttributeValue.builder().s(createdAt).build());
			item.put("body",
					AttributeValue.builder().m(
							er.getContent().entrySet().stream().collect(Collectors.toMap(
									Map.Entry::getKey,
									e -> AttributeValue.builder().s(e.getValue()).build()
							))
					).build()
			);

			ddb.putItem(PutItemRequest.builder()
					.tableName(tableName)
					.item(item)
					.build());

			Map<String,Object> event = Map.of(
					"id",          id,
					"principalId", er.getPrincipalId(),
					"createdAt",   createdAt,
					"body",        er.getContent()
			);
			Map<String,Object> payload = Map.of(
					"statusCode", 201,
					"event",      event
			);
			String bodyString = om.writeValueAsString(payload);

			return new APIGatewayProxyResponseEvent()
					.withStatusCode(201)
					.withHeaders(Map.of("Content-Type","application/json"))
					.withBody(bodyString);

		} catch (Exception e) {
			ctx.getLogger().log("ERR: " + e);
			String err = "{\"message\":\""+e.getMessage()+"\"}";
			return new APIGatewayProxyResponseEvent()
					.withStatusCode(500)
					.withHeaders(Map.of("Content-Type","application/json"))
					.withBody(err);
		}
	}

	public static class EventRequest {
		private int principalId;
		private Map<String,String> content;
		public int getPrincipalId() { return principalId; }
		public void setPrincipalId(int p) { principalId = p; }
		public Map<String,String> getContent() { return content; }
		public void setContent(Map<String,String> c) { content = c; }
	}
}