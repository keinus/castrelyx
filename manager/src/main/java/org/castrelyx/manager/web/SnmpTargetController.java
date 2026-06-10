package org.castrelyx.manager.web;

import java.util.List;
import org.castrelyx.manager.snmp.SnmpTarget;
import org.castrelyx.manager.snmp.SnmpTargetRequest;
import org.castrelyx.manager.snmp.SnmpTargetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/snmp/targets")
public class SnmpTargetController {
  private final SnmpTargetService snmpTargetService;

  public SnmpTargetController(SnmpTargetService snmpTargetService) {
    this.snmpTargetService = snmpTargetService;
  }

  @GetMapping
  public List<SnmpTarget> list() {
    return snmpTargetService.list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SnmpTarget create(@RequestBody SnmpTargetRequest request) {
    return snmpTargetService.create(request);
  }

  @PutMapping("/{id}")
  public SnmpTarget update(@PathVariable long id, @RequestBody SnmpTargetRequest request) {
    return snmpTargetService.update(id, request);
  }

  @PostMapping("/{id}/enable")
  public SnmpTarget enable(@PathVariable long id) {
    return snmpTargetService.enable(id, true);
  }

  @PostMapping("/{id}/disable")
  public SnmpTarget disable(@PathVariable long id) {
    return snmpTargetService.enable(id, false);
  }
}
