# API Chaining Functionality

## Overview

The HTTP Source Connector supports API chaining functionality that allows you to create parent-child relationships between API endpoints. This enables complex data retrieval patterns where child APIs depend on data from parent API responses.

## How API Chaining Works

1. **Parent APIs** are executed first and their response data is stored
2. **Child APIs** wait for their parent API to complete before executing
3. Child APIs can use template variables extracted from parent responses
4. The connector supports one level of chaining (parent → child)

## Configuration

### Parent-Child Relationship

Define relationships using the `api.chaining.parent.child.relationship` property:

```json
{
  "api.chaining.parent.child.relationship": "child1:parent1,child2:parent1,child3:parent2"
}
```

**Format**: `child_api_id:parent_api_id,child_api_id:parent_api_id,...`

### Chaining JSON Pointer

For each child API, specify how to extract values from the parent response using the `http.chaining.json.pointer` property:

```json
{
  "api2.http.chaining.json.pointer": "/id",
  "api3.http.chaining.json.pointer": "/company_id"
}
```

### Template Variables

Child APIs can use these template variables in their configuration:

- `${parent_value}` - The value extracted using the chaining JSON pointer
- `${parent_api_id}` - The ID of the parent API
- `${offset}` - Standard offset variable (still available)

## Example Configuration

### Scenario: Company → Employees & Departments

```json
{
  "name": "company-data-connector",
  "config": {
    "connector.class": "io.confluent.connect.http.HttpSourceConnector",
    "tasks.max": "1",
    "http.api.base.url": "https://api.company.com/v1",
    "auth.type": "OAUTH2",
    "oauth2.token.url": "https://auth.company.com/oauth/token",
    "oauth2.client.id": "client-id",
    "oauth2.client.secret": "client-secret",
    
    "apis.num": "3",
    "api.chaining.parent.child.relationship": "employees_api:companies_api,departments_api:companies_api",
    
    // Parent API: Get all companies
    "api1.http.api.path": "/companies",
    "api1.topics": "companies-topic",
    "api1.http.request.method": "GET",
    "api1.http.offset.mode": "CURSOR_PAGINATION",
    "api1.http.next.page.json.pointer": "/pagination/next_cursor",
    "api1.http.response.data.json.pointer": "/companies",
    "api1.request.interval.ms": "60000",
    
    // Child API 1: Get employees for each company
    "api2.http.api.path": "/companies/${parent_value}/employees",
    "api2.topics": "employees-topic",
    "api2.http.request.method": "GET",
    "api2.http.offset.mode": "SIMPLE_INCREMENTING",
    "api2.http.chaining.json.pointer": "/id",
    "api2.http.response.data.json.pointer": "/employees",
    "api2.request.interval.ms": "30000",
    
    // Child API 2: Get departments for each company
    "api3.http.api.path": "/companies/${parent_value}/departments",
    "api3.topics": "departments-topic", 
    "api3.http.request.method": "GET",
    "api3.http.offset.mode": "SIMPLE_INCREMENTING",
    "api3.http.chaining.json.pointer": "/id",
    "api3.http.response.data.json.pointer": "/departments",
    "api3.request.interval.ms": "30000",
    
    "output.data.format": "JSON_SR",
    "behavior.on.error": "FAIL"
  }
}
```

## Data Flow Example

### 1. Parent API Response
```json
{
  "companies": [
    {"id": "company-123", "name": "Acme Corp"},
    {"id": "company-456", "name": "Tech Solutions"}
  ],
  "pagination": {
    "next_cursor": "cursor-abc123"
  }
}
```

### 2. Child API Requests
Based on the parent response, child APIs will make requests:

**Employees API**:
- `/companies/company-123/employees`
- `/companies/company-456/employees`

**Departments API**:
- `/companies/company-123/departments`
- `/companies/company-456/departments`

## Execution Flow

1. **Initialization**: Connector validates chaining configuration for circular dependencies
2. **Parent API Execution**: Parent APIs are polled according to their intervals
3. **Data Storage**: Parent responses are stored in memory for child APIs
4. **Child API Execution**: Child APIs wait for parent data, then execute with template variables
5. **Record Production**: All APIs produce records to their respective Kafka topics

## Features

### ✅ Supported
- One level of parent-child relationships
- Multiple child APIs per parent
- Template variable substitution in URLs, headers, and request bodies
- JSON pointer-based value extraction from parent responses
- Validation for circular dependencies
- Independent offset management per API

### ❌ Not Supported
- Multi-level chaining (grandparent → parent → child)
- Cross-connector chaining
- Persistent parent data storage (restarted connectors need to re-fetch parent data)

## Configuration Validation

The connector validates chaining configuration at startup:

1. **Circular Dependency Check**: Ensures no API depends on itself through chaining
2. **API Existence Check**: Verifies all referenced parent and child APIs exist
3. **JSON Pointer Validation**: Checks that chaining JSON pointers are valid

### Common Validation Errors

```
ConfigException: Circular dependency detected in API chaining configuration involving: api1
ConfigException: Parent API 'parent_api' referenced by child API 'child_api' does not exist in configuration
ConfigException: Child API 'child_api' in chaining configuration does not exist in API configuration
```

## Best Practices

### 1. API Naming
Use descriptive API IDs that clearly indicate the hierarchy:
```json
{
  "api.chaining.parent.child.relationship": "company_employees:companies,company_departments:companies"
}
```

### 2. Request Intervals
Set appropriate intervals to avoid overwhelming APIs:
- Parent APIs: Longer intervals (60s+) since they provide base data
- Child APIs: Shorter intervals (30s+) for more frequent updates

### 3. Error Handling
- Use `"behavior.on.error": "IGNORE"` for non-critical child APIs
- Implement proper retry logic for parent APIs since child APIs depend on them

### 4. Memory Management
- Parent response data is stored in memory
- Large parent responses may impact connector performance
- Consider pagination for parent APIs with large datasets

## Monitoring and Troubleshooting

### Log Messages
```
INFO  - Initialized API chaining with 2 parent-child relationships
DEBUG - Parent-child relationships: {child1=parent1, child2=parent1}
DEBUG - Stored parent API response for chaining: parent1
DEBUG - Child API child1 waiting for parent API parent1 to provide data
```

### Common Issues

1. **Child APIs Not Executing**
   - Check that parent API has successfully executed and produced data
   - Verify parent-child relationship configuration
   - Ensure chaining JSON pointer correctly extracts values

2. **Template Variable Not Replaced**
   - Verify the JSON pointer path in parent response
   - Check that parent response contains expected data structure
   - Ensure template variable syntax is correct (`${parent_value}`)

3. **Performance Issues**
   - Monitor memory usage if parent responses are large
   - Adjust request intervals to balance freshness vs. load
   - Consider reducing parent response data using JSON pointers

## Implementation Details

### Classes Involved
- `ApiChainingManager`: Manages parent-child relationships and data storage
- `HttpSourceTask`: Integrates chaining logic into polling workflow
- `HttpApiClient`: Supports additional template variables for child APIs
- `ApiConfig`: Provides chaining configuration properties

### Data Storage
- Parent response data is stored in-memory using `ConcurrentHashMap`
- Data is refreshed each time the parent API is polled
- No persistent storage - connector restarts require re-fetching parent data

This implementation provides a robust foundation for API chaining while maintaining performance and reliability standards expected from enterprise Kafka connectors.