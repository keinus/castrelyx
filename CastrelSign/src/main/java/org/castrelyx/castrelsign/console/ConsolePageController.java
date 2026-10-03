package org.castrelyx.castrelsign.console;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ConsolePageController {
  @GetMapping({"/", "/console"})
  public String entry() { return "redirect:/console/"; }

  @GetMapping("/console/")
  public String console() { return "forward:/console/index.html"; }
}
