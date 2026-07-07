package org.castrelyx.castrelvault.integration;

import java.util.List;
import java.util.Map;

public interface CastrelSignApplicationAdminClient {
  Status status();

  List<Map<String, Object>> applications();

  Map<String, Object> createApplication(Map<String, Object> request);

  Map<String, Object> grantPermission(String principalId, Map<String, Object> request);

  void block(String principalId);

  void reactivate(String principalId);

  List<Map<String, Object>> certificates();

  List<Map<String, Object>> tokens();

  Map<String, Object> createToken(Map<String, Object> request);

  void revokeToken(long id);

  record Status(boolean configured, String baseUrl, String state, String detail) {
  }
}
