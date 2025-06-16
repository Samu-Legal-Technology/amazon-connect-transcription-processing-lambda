# Amazon Connect Transcription Processing Lambda

## Overview

This AWS Lambda function processes Amazon Connect call transcriptions stored in S3. It's triggered by S3 events (via SQS) when new transcript files are created, extracts the transcript content, and updates the corresponding contact record in DynamoDB with the full transcript text and metadata.

## Architecture

```
┌─────────────────┐     ┌─────────────┐     ┌──────────┐     ┌─────────────────┐
│ Amazon Connect  │────▶│ Transcribe  │────▶│    S3    │────▶│      SQS        │
│  (Call Audio)   │     │  Service    │     │ (JSON)   │     │ (Event Queue)   │
└─────────────────┘     └─────────────┘     └──────────┘     └────────┬────────┘
                                                                        │
                                                                        ▼
                                                              ┌─────────────────┐
                                                              │     Lambda      │
                                                              │   (Process)     │
                                                              └────────┬────────┘
                                                                        │
                                                                        ▼
                                                              ┌─────────────────┐
                                                              │    DynamoDB     │
                                                              │ (Update Record) │
                                                              └─────────────────┘
```

## Features

- **Event-Driven Processing**: Automatically triggered when transcripts are ready
- **SQS Integration**: Handles batch processing of multiple transcript events
- **S3 File Processing**: Reads JSON transcript files from S3 buckets
- **DynamoDB Updates**: Enriches existing contact records with transcript data
- **Contact Correlation**: Links transcripts to contacts using ContactId
- **Status Tracking**: Updates record status to "Completed" after processing

## Technical Stack

- **Java 8**: Core programming language
- **Spring Boot 2.5.6**: Application framework
- **AWS Lambda**: Serverless compute
- **AWS SDK**: S3, DynamoDB, and SQS integration
- **Maven**: Build and dependency management

## Prerequisites

- Java 8 or higher
- Maven 3.6+
- AWS Account with appropriate permissions
- Existing DynamoDB table: `OttConnectContactTraceRecord-Dev`

## Expected Input Format

The Lambda expects transcript files in S3 with the following JSON structure:

```json
{
  "CustomerMetadata": {
    "ContactId": "12345678-1234-1234-1234-123456789012"
  },
  "Transcript": [
    {
      "ParticipantId": "AGENT",
      "Content": "Hello, how can I help you?",
      "BeginOffsetMillis": 0,
      "EndOffsetMillis": 2000
    },
    {
      "ParticipantId": "CUSTOMER",
      "Content": "I need help with my account",
      "BeginOffsetMillis": 2500,
      "EndOffsetMillis": 4500
    }
  ]
}
```

## Installation & Build

1. Clone the repository:
```bash
git clone https://github.com/Samu-Legal-Technology/amazon-connect-transcription-processing-lambda.git
cd amazon-connect-transcription-processing-lambda
```

2. Build the project:
```bash
mvn clean package
```

3. The Lambda deployment package will be created at:
```
target/amazon-connect-transcript-processing-lambda-0.0.1-SNAPSHOT.jar
```

## Deployment

### Lambda Function Setup

1. Create a new Lambda function in AWS Console
2. Runtime: Java 8 (Corretto)
3. Handler: `com.ott.connect.SqsEventHandler::handleRequest`
4. Memory: 512 MB (recommended)
5. Timeout: 1 minute
6. Upload the JAR file from target directory

### Environment Variables

Currently, the following values are hardcoded and should be externalized:

| Variable | Current Value | Description |
|----------|---------------|-------------|
| `AWS_REGION` | `us-east-1` | AWS region |
| `DYNAMODB_TABLE` | `OttConnectContactTraceRecord-Dev` | DynamoDB table name |

### IAM Permissions

The Lambda execution role requires:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes"
      ],
      "Resource": "arn:aws:sqs:*:*:*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject"
      ],
      "Resource": "arn:aws:s3:::*/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:GetItem",
        "dynamodb:UpdateItem"
      ],
      "Resource": "arn:aws:dynamodb:*:*:table/OttConnectContactTraceRecord-*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "arn:aws:logs:*:*:*"
    }
  ]
}
```

### SQS Trigger Configuration

1. Create an SQS queue for S3 events
2. Configure S3 bucket to send events to SQS
3. Add SQS as Lambda trigger:
   - Batch size: 10
   - Batch window: 0 seconds

### S3 Event Configuration

Configure your S3 bucket to send events to SQS for transcript files:
```
Event types: s3:ObjectCreated:*
Prefix: transcripts/
Suffix: .json
```

## Data Flow

1. **Amazon Connect** completes a call
2. **Audio Recording** is sent to Amazon Transcribe
3. **Transcribe** generates JSON transcript and saves to S3
4. **S3 Event** triggers SQS message
5. **Lambda** processes SQS event:
   - Reads transcript from S3
   - Extracts ContactId and transcript array
   - Updates DynamoDB record

## DynamoDB Schema

### Table: OttConnectContactTraceRecord-Dev

**Key Structure:**
- Partition Key: `ContactId` (String)

**Updated Attributes:**
- `Transcript`: Full transcript array
- `TranscriptS3Location`: S3 file path
- `UpdatedAt`: Timestamp of last update
- `Status`: Set to "Completed"

## Development

### Local Testing

Create a test event simulating SQS message:

```java
SQSEvent testEvent = new SQSEvent();
SQSMessage message = new SQSMessage();
message.setBody("{\"Records\":[{\"s3\":{\"bucket\":{\"name\":\"test-bucket\"},\"object\":{\"key\":\"test-key.json\"}}}]}");
testEvent.setRecords(Collections.singletonList(message));

new SqsEventHandler().handleRequest(testEvent, null);
```

### Logging

Add comprehensive logging:
```java
private static final Logger logger = LoggerFactory.getLogger(SqsEventHandler.class);
logger.info("Processing transcript for ContactId: {}", contactId);
```

## Monitoring

- **CloudWatch Logs**: Monitor Lambda execution logs
- **SQS Metrics**: Track message processing rate
- **DynamoDB Metrics**: Monitor update operations
- **Lambda Metrics**: Track invocations, errors, duration

## Error Handling

### Current Implementation
- Basic try-catch with stack trace printing
- No retry logic

### Recommended Improvements
1. Implement exponential backoff for transient errors
2. Configure SQS Dead Letter Queue
3. Add structured logging with correlation IDs
4. Implement custom CloudWatch metrics

## Performance Optimization

1. **Batch Processing**: Current implementation processes messages individually
2. **Connection Pooling**: Reuse AWS SDK clients
3. **Parallel Processing**: Process multiple transcripts concurrently
4. **Memory Tuning**: Adjust Lambda memory based on transcript size

## Security Considerations

- Enable S3 bucket encryption
- Use VPC endpoints for AWS services
- Implement least-privilege IAM policies
- Enable DynamoDB encryption at rest
- Audit S3 access logs

## Troubleshooting

### Common Issues

1. **ContactId Not Found**: Ensure contact record exists before transcript
2. **S3 Access Denied**: Verify Lambda role has GetObject permission
3. **DynamoDB Throttling**: Increase table capacity or implement retry
4. **Large Transcripts**: Increase Lambda memory/timeout

### Debug Steps

1. Check CloudWatch logs for error messages
2. Verify S3 event configuration
3. Test SQS queue permissions
4. Validate JSON format in S3

## Future Enhancements

- [ ] Add environment variable configuration
- [ ] Implement comprehensive error handling
- [ ] Add metrics and monitoring
- [ ] Support for batch DynamoDB updates
- [ ] Implement dead letter queue processing
- [ ] Add unit and integration tests
- [ ] Support for multiple transcript formats
- [ ] Add transcript analysis features

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Submit a pull request

## License

Copyright © 2024 Samu Legal Technology. All rights reserved.

---

*Maintained by Samu Legal Technology Development Team*