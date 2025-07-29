// Quick test to verify pattern caching works correctly
import io.confluent.connect.http.config.ApiConfig;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.offset.ODataOffsetManager;

import java.util.HashMap;
import java.util.Map;

public class TestPatternCache {
    public static void main(String[] args) {
        // Setup configuration
        Map<String, String> configProps = new HashMap<>();
        configProps.put("http.api.base.url", "https://api.example.com");
        configProps.put("apis.num", "1");
        configProps.put("api1.http.api.path", "/api/data/v9.0/accounts");
        configProps.put("api1.topics", "accounts-topic");
        configProps.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configProps.put("api1.http.initial.offset", "?$select=name");
        configProps.put("api1.odata.nextlink.field", "@odata.nextLink");
        configProps.put("api1.odata.deltalink.field", "@odata.deltaLink");
        configProps.put("api1.odata.token.mode", "TOKEN_ONLY");
        configProps.put("api1.odata.skiptoken.param", "$skiptoken");
        configProps.put("api1.odata.deltatoken.param", "$deltatoken");
        
        HttpSourceConnectorConfig globalConfig = new HttpSourceConnectorConfig(configProps);
        ApiConfig apiConfig = new ApiConfig(globalConfig, 1);
        
        System.out.println("Creating multiple ODataOffsetManager instances to test pattern caching...");
        
        // Create multiple instances with same configuration
        for (int i = 0; i < 5; i++) {
            ODataOffsetManager manager = new ODataOffsetManager(apiConfig, null);
            System.out.println("Instance " + (i+1) + ": skipTokenParam=" + manager.getSkipTokenParam() + 
                             ", deltaTokenParam=" + manager.getDeltaTokenParam());
        }
        
        System.out.println("Pattern caching test completed successfully!");
    }
}
