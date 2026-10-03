package org.castrelyx.manager.web;

import org.castrelyx.manager.telemetry.ClickHouseClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AssetLogsControllerTest {
  @Test void distinguishesEmptyLogsFromStorageFailureAndValidatesAssetAndFilters() {
    var db=mock(JdbcTemplate.class);var clickHouse=mock(ClickHouseClient.class);
    var logparser=mock(org.castrelyx.manager.integration.LogparserClient.class);
    when(logparser.routerStatus(anyString())).thenReturn(Map.of("available",false));
    var controller=new AssetLogsController(clickHouse,db,logparser);
    when(db.queryForObject(anyString(),eq(Integer.class),eq("asset-1"))).thenReturn(1);
    when(clickHouse.queryAssetLogEvents("1h","ALL","asset-1","zeek","",100,0)).thenReturn(Map.of("items",java.util.List.of()));
    assertEquals(java.util.List.of(),controller.logs("asset-1","1h","ALL","zeek","",100,0).get("items"));
    when(clickHouse.queryAssetLogEvents("1h","ALL","asset-1","zeek","",100,0)).thenThrow(new IllegalStateException("offline"));
    assertEquals(503,assertThrows(ResponseStatusException.class,()->controller.logs("asset-1","1h","ALL","zeek","",100,0)).getStatusCode().value());
    assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.logs("asset-1","1h","ALL","invalid","",100,0)).getStatusCode().value());
    assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.logs("asset-1","1h","ALL","ALL","",100,-1)).getStatusCode().value());
    assertEquals(404,assertThrows(ResponseStatusException.class,()->controller.logs("missing","1h","ALL","ALL","",100,0)).getStatusCode().value());
  }
}
