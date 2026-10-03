package org.castrelyx.manager.web;

import java.util.Map;
import org.castrelyx.manager.telemetry.ClickHouseClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/assets/by-uid/{assetUid}/logs")
public class AssetLogsController {
  private final ClickHouseClient clickHouse;
  private final JdbcTemplate jdbc;
  private final org.castrelyx.manager.integration.LogparserClient logparser;
  public AssetLogsController(ClickHouseClient clickHouse, JdbcTemplate jdbc, org.castrelyx.manager.integration.LogparserClient logparser) { this.clickHouse=clickHouse; this.jdbc=jdbc; this.logparser=logparser; }
  @GetMapping
  public Map<String,Object> logs(@PathVariable String assetUid,
      @RequestParam(defaultValue="1h") String range,
      @RequestParam(defaultValue="ALL") String severity,
      @RequestParam(defaultValue="ALL") String family,
      @RequestParam(defaultValue="") String query,
      @RequestParam(defaultValue="100") int limit,
      @RequestParam(defaultValue="0") int offset) {
    Integer count=jdbc.queryForObject("select count(*) from assets where asset_uid=?",Integer.class,assetUid);
    if (count==null || count==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Asset not registered");
    if (offset<0 || offset>100000 || limit<1 || limit>300 || query.length()>256 ||
        !java.util.Set.of("ALL","audit","security","zeek").contains(family) ||
        !java.util.Set.of("ALL","INFO","WARNING","ERROR","CRITICAL").contains(severity) ||
        !java.util.Set.of("15m","30m","1h","6h","24h").contains(range))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid log filter");
    try {
      var page=new java.util.LinkedHashMap<>(clickHouse.queryAssetLogEvents(range,severity,assetUid,family,query,limit,offset));
      page.put("receiver",logparser.routerStatus(assetUid));
      return page;
    }
    catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Log storage unavailable",e); }
  }
}
