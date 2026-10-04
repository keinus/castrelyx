package org.castrelyx.manager.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.castrelyx.manager.auth.AuthUser;
import org.castrelyx.manager.auth.CurrentUser;
import org.castrelyx.manager.router.RouterControlService;
import org.castrelyx.manager.router.RouterControlService.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assets/{assetUid}/router-control")
public class RouterControlController {
  private final RouterControlService service;
  public RouterControlController(RouterControlService service) {this.service=service;}
  @GetMapping("/binding")
  public Binding binding(@PathVariable String assetUid) {return service.binding(assetUid);}
  @PutMapping("/binding")
  public Binding bind(@PathVariable String assetUid,@RequestBody BindRequest body,HttpServletRequest request) {return service.bind(assetUid,body,user(request));}
  public record Operation(String requestId,Map<String,Object> request) {}
  @PostMapping("/queries") @ResponseStatus(HttpStatus.ACCEPTED)
  public Command query(@PathVariable String assetUid,@RequestBody Operation body,HttpServletRequest request) {return service.create(assetUid,body.requestId(),body.request(),true,user(request));}
  @PostMapping("/commands") @ResponseStatus(HttpStatus.ACCEPTED)
  public Command create(@PathVariable String assetUid,@RequestBody Operation body,HttpServletRequest request) {return service.create(assetUid,body.requestId(),body.request(),false,user(request));}
  @GetMapping("/commands")
  public List<Command> history(@PathVariable String assetUid) {return service.history(assetUid);}
  @GetMapping("/commands/{id}")
  public Command get(@PathVariable String assetUid,@PathVariable String id) {return service.get(assetUid,id);}
  private static AuthUser user(HttpServletRequest request) {return (AuthUser)request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE);}
}
