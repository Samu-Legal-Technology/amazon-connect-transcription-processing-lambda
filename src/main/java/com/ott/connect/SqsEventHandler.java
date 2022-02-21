package com.ott.connect;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.util.StreamUtils;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.PutItemOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

@SpringBootApplication
public class SqsEventHandler implements RequestHandler<SQSEvent, Void> {

	private static Logger logger = LoggerFactory.getLogger(SqsEventHandler.class);
	static Supplier<AmazonS3> s3client = () -> AmazonS3ClientBuilder.standard().withRegion(Regions.US_EAST_1).build();
	public static final Supplier<AmazonDynamoDB> dynamoDBClient = () -> AmazonDynamoDBClientBuilder.standard().build();

	public final SimpleDateFormat dateFormate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	@Override
	public Void handleRequest(SQSEvent input, Context context) {
		LambdaLogger lambdaLogger = context.getLogger();
		lambdaLogger.log("SQS Input : " + input);
		for (SQSMessage msg : input.getRecords()) {
			lambdaLogger.log(msg.getBody());
			try {

				JSONObject sqsMsgJsonObject = new JSONObject(msg.getBody());
				lambdaLogger.log("JSON Object => " + sqsMsgJsonObject);
				JSONArray jsonRecordsArray = sqsMsgJsonObject.getJSONArray("Records");
				JSONObject s3EventDetails = jsonRecordsArray.getJSONObject(0).getJSONObject("s3");
				lambdaLogger.log("S3 Object : " + s3EventDetails.toString());

				JSONObject s3BucketDetails = s3EventDetails.getJSONObject("bucket");
				lambdaLogger.log("s3BucketDetails : " + s3BucketDetails.toString());

				JSONObject s3ObjectDetails = s3EventDetails.getJSONObject("object");
				lambdaLogger.log("s3ObjectDetails : " + s3ObjectDetails.toString());

				lambdaLogger.log("==================================================");
				String transcriptString = getS3ObjectContentAsString(s3BucketDetails.getString("name"),
						s3ObjectDetails.getString("key"));

				lambdaLogger.log("++++++++++++++++++++++++++++++++++++++++++++++++");
				lambdaLogger.log("Transcript File Reading Started.......");
				JSONObject transcriptJsonObject = new JSONObject(transcriptString);
				JSONArray transcript = transcriptJsonObject.getJSONArray("Transcript");
				JSONObject transcriptObject=new JSONObject();
				transcriptObject.put("Transcripts", transcript);
				String contactId = transcriptJsonObject.getJSONObject("CustomerMetadata").getString("ContactId");

				lambdaLogger.log("Transcript : " + transcript.toString());
				lambdaLogger.log("Contact ID : " + contactId);

				Map<String, String> transcriptFileDetails = new HashMap<>();
				transcriptFileDetails.put("S3BucketName", s3BucketDetails.getString("name"));
				transcriptFileDetails.put("S3Key", s3ObjectDetails.getString("key"));

				DynamoDB db = new DynamoDB(dynamoDBClient.get());
				Table table = db.getTable("OttConnectContactTraceRecord-Dev");

				Item item = table.getItem("ContactId",contactId);
				lambdaLogger.log("Is item available ? : "+(item!=null));
				
				if(item!=null) {
				item = item.withPrimaryKey("ContactId", contactId)
						.withJSON("Transcript", transcriptObject.toString())
						.withString("UpdatedAt", dateFormate.format(new Date()))
						.withList("TrascriptS3URI", transcriptFileDetails)
						.withString("Status", "Completed");
				
				PutItemOutcome putItem = table.putItem(item);
				if (putItem.getPutItemResult().getSdkHttpMetadata().getHttpStatusCode() == 200)
					lambdaLogger.log("Data saved in db");
				}else {
					lambdaLogger.log("Item("+item.getString("ContactId")+") does not exists in DynamoDB.........");
				}
				

			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return null;
	}

	public static String getS3ObjectContentAsString(String bucketName, String key) {
		try {
			if (key.startsWith("/")) {
				key = key.substring(1);
			}
			if (key.endsWith("/")) {
				key = key.substring(0, key.length());
			}

			logger.info("S3 Read File Input : ");
			logger.info("bucketName : {}" , bucketName);
			logger.info("Key : {}" , key);
			try (InputStream is = s3client.get().getObject(bucketName, key.replace("%3A", ":")).getObjectContent()) {
				return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
			}
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	

}