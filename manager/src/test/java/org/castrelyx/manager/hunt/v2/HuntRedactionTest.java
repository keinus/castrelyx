package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.castrelyx.manager.auth.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HuntRedactionTest {
  @Test
  void exceptionEvidenceKeepsOriginalAndRoleCopyHashesSeparateWithoutLeakingSerializedText()
      throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var original =
        Map.of(
            "actor",
            "alice",
            "message",
            "Authorization: Bearer private-credential",
            "event_json",
            "private raw");
    String encoded = json.writeValueAsString(original);
    var data =
        Map.of(
            "evidence",
            original,
            "evidenceJson",
            encoded,
            "evidenceSha256",
            HuntWorkspace.hash(encoded));
    for (Role role : List.of(Role.OPERATOR, Role.VIEWER)) {
      @SuppressWarnings("unchecked")
      var copy = (Map<String, Object>) HuntRedaction.apply(data, new AuthUser(1, "qa", "qa", role));
      assertThat(String.valueOf(copy))
          .doesNotContain("private-credential", "private raw")
          .contains("alice");
      assertThat(copy)
          .containsEntry("redacted", true)
          .containsEntry("evidenceSha256", HuntWorkspace.hash(encoded));
      assertThat(copy.get("exportEvidenceSha256"))
          .isEqualTo(HuntWorkspace.hash((String) copy.get("evidenceJson")));
      assertThat(copy.get("exportEvidenceSha256")).isNotEqualTo(copy.get("evidenceSha256"));
    }
    assertThat(HuntRedaction.apply(data, new AuthUser(2, "admin", "admin", Role.ADMIN)))
        .isSameAs(data);
  }

  @ParameterizedTest
  @ValueSource(ints = {1024, 4096, 32768, 65536})
  void longQuotedProtectedFieldsNeverOverflowTheStackOrExposeTheirContents(int size) {
    for (String secret :
        List.of("credential".repeat(size / 10), "escaped\\\"credential".repeat(size / 20))) {
      var data =
          Map.of(
              "command_line",
              "tool password=\"" + secret + "\" --host public-host",
              "message",
              "Authorization: \"Bearer " + secret + "\"; actor=alice");
      String copy =
          String.valueOf(HuntRedaction.apply(data, new AuthUser(1, "qa", "qa", Role.OPERATOR)));
      assertThat(copy).doesNotContain("credential").contains("public-host", "actor=alice", "마스킹");
      assertThat(HuntRedaction.apply(data, new AuthUser(2, "admin", "admin", Role.ADMIN)))
          .isSameAs(data);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void optionalQueryDefaultsCannotHideTheRedactedReplayMarker(int optionalFields) {
    var query = new LinkedHashMap<String, Object>();
    query.put("from", "2026-10-20T00:00:00Z");
    query.put("to", "2026-10-20T00:01:00Z");
    query.put(
        "filter",
        Map.of(
            "op",
            "not",
            "children",
            List.of(
                Map.of(
                    "op",
                    "contains",
                    "field",
                    "message",
                    "values",
                    List.of("token=omitted-default-secret")))));
    if ((optionalFields & 1) != 0) query.put("text", null);
    if ((optionalFields & 2) != 0) query.put("groupBy", null);
    for (Role role : List.of(Role.VIEWER, Role.OPERATOR)) {
      @SuppressWarnings("unchecked")
      var copy =
          (Map<String, Object>) HuntRedaction.apply(query, new AuthUser(1, "qa", "qa", role));
      assertThat(copy).containsEntry("redacted", true);
      assertThat(String.valueOf(copy)).doesNotContain("omitted-default-secret");
      assertThat(copy.keySet()).containsAll(query.keySet());
    }
    assertThat(HuntRedaction.apply(query, new AuthUser(2, "admin", "admin", Role.ADMIN)))
        .isSameAs(query);
    assertThat(String.valueOf(query)).contains("omitted-default-secret");
  }

  @Test
  void minimalPublicQueriesStayReplayableWhileFreeTextIsProtectedWithoutGroupDefaults() {
    var query = new LinkedHashMap<String, Object>();
    query.put("from", "2026-10-20T00:00:00Z");
    query.put("to", "2026-10-20T00:01:00Z");
    query.put("filter", Map.of("op", "eq", "field", "actor", "values", List.of("alice")));
    for (Role role : List.of(Role.VIEWER, Role.OPERATOR)) {
      @SuppressWarnings("unchecked")
      var copy =
          (Map<String, Object>) HuntRedaction.apply(query, new AuthUser(1, "qa", "qa", role));
      assertThat(copy).isEqualTo(query).doesNotContainKey("redacted");
    }
    query.put("text", "Authorization: Bearer free-text-credential");
    for (Role role : List.of(Role.VIEWER, Role.OPERATOR)) {
      @SuppressWarnings("unchecked")
      var copy =
          (Map<String, Object>) HuntRedaction.apply(query, new AuthUser(1, "qa", "qa", role));
      assertThat(copy).containsEntry("redacted", true);
      assertThat(copy.get("text")).asString().doesNotContain("free-text-credential");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Authorization: Bearer header-credential",
        "authorization :  bEaReR\theader-credential",
        "Authorization=Basic header-credential==",
        "curl -H 'Authorization: Bearer header-credential' https://example.invalid/path",
        "curl -H \"Authorization: Basic header-credential==\" https://example.invalid/path",
        "\"Authorization\": \"Bearer header-credential\"",
        "'Authorization' = 'Basic header-credential=='",
        "Authorization Bearer header-credential; host=normal-host",
        "Authorization: Bearer header-credential, next=visible",
        "Proxy-Authorization: Basic header-credential==",
        "\"token\": \"header-credential\"",
        "'api_key'='header-credential'",
        "password=\"escaped\\\"header-credential\"",
        "secret='escaped\\'header-credential'"
      })
  void supportedCredentialAssignmentsAndAuthorizationHeadersAreFullyMasked(String text) {
    for (String field : List.of("command_line", "message")) {
      var data = Map.of(field, text, "actor", "normal-actor");
      for (Role role : List.of(Role.VIEWER, Role.OPERATOR)) {
        String copy = String.valueOf(HuntRedaction.apply(data, new AuthUser(1, "qa", "qa", role)));
        assertThat(copy).doesNotContain("header-credential").contains("normal-actor");
      }
      assertThat(HuntRedaction.apply(data, new AuthUser(2, "admin", "admin", Role.ADMIN)))
          .isSameAs(data);
    }
  }

  @Test
  void maskingPreservesPublicIdentifiersAndFollowingCommandArguments() {
    var data =
        Map.of(
            "command_line",
            "curl -H 'Authorization: Bearer hidden-value' --host normal-host; token=second-value, actor=alice; mypassword=normal-name tokenization=normal-token");
    String copy =
        String.valueOf(HuntRedaction.apply(data, new AuthUser(1, "qa", "qa", Role.OPERATOR)));
    assertThat(copy)
        .doesNotContain("hidden-value", "second-value")
        .contains(
            "--host normal-host",
            "actor=alice",
            "mypassword=normal-name",
            "tokenization=normal-token");
  }

  @Test
  void typedAndPersistedQueryMetadataCannotRevealHiddenSearchValues() {
    var now = Instant.now();
    var query =
        new HuntQuery(
            now.minusSeconds(60),
            now,
            new HuntQuery.Filter(
                "and",
                null,
                null,
                List.of(
                    new HuntQuery.Filter("eq", "actor", List.of("alice"), null),
                    new HuntQuery.Filter(
                        "contains", "command_line", List.of("tool token=query-secret"), null))),
            "password=query-secret",
            "actor",
            "count",
            50,
            null);
    var json =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    for (Object spec : List.of(query, json.convertValue(query, Map.class))) {
      Object result = Map.of("query", spec);
      String viewer =
          String.valueOf(HuntRedaction.apply(result, new AuthUser(1, "v", "v", Role.VIEWER)));
      String operator =
          String.valueOf(HuntRedaction.apply(result, new AuthUser(2, "o", "o", Role.OPERATOR)));
      assertThat(viewer).contains("alice", "원문 조회 권한 필요").doesNotContain("query-secret");
      assertThat(operator).contains("alice", "마스킹").doesNotContain("query-secret");
      assertThat(String.valueOf(HuntRedaction.apply(result, new AuthUser(3, "a", "a", Role.ADMIN))))
          .contains("query-secret");
    }
  }

  @Test
  void privateSecondaryDimensionIsMaskedWithoutLeakingAnUnlabelledRawAlias() {
    var now = Instant.now();
    var query =
        new HuntQuery(
            now.minusSeconds(60),
            now,
            null,
            "",
            "actor",
            "count",
            200,
            null,
            List.of("actor", "command_line"),
            false);
    var result =
        Map.of(
            "query",
            query,
            "groups",
            List.of(
                Map.of(
                    "value",
                    "alice",
                    "dimensions",
                    Map.of("actor", "alice", "command_line", "tool password=secret-value"))));
    String operator =
        String.valueOf(HuntRedaction.apply(result, new AuthUser(2, "o", "o", Role.OPERATOR)));
    String viewer =
        String.valueOf(HuntRedaction.apply(result, new AuthUser(2, "v", "v", Role.VIEWER)));
    assertThat(operator).contains("alice", "마스킹").doesNotContain("secret-value");
    assertThat(viewer).contains("alice", "원문 조회 권한 필요").doesNotContain("secret-value");
  }

  @Test
  void redactsRawEvidenceRecursivelyAndMasksKnownSecrets() {
    Object data =
        Map.of(
            "items",
            List.of(
                Map.of(
                    "event_json",
                    "secret raw",
                    "command_line",
                    "tool token=abc password=def",
                    "actor",
                    "alice")));
    String viewer =
        String.valueOf(HuntRedaction.apply(data, new AuthUser(1, "v", "v", Role.VIEWER)));
    assertThat(viewer).doesNotContain("secret raw", "abc", "def").contains("alice");
    String operator =
        String.valueOf(HuntRedaction.apply(data, new AuthUser(2, "o", "o", Role.OPERATOR)));
    assertThat(operator).contains("마스킹").doesNotContain("abc", "def", "secret raw");
  }

  @Test
  void completedCancellationResponseIsRedactedJustLikePolling() {
    var job =
        new HuntJobs.Job(
            "x",
            "COMPLETED",
            Instant.EPOCH,
            Instant.EPOCH,
            null,
            Map.of("event_json", "sensitive"));
    assertThat(String.valueOf(HuntRedaction.apply(job, new AuthUser(1, "v", "v", Role.VIEWER))))
        .doesNotContain("sensitive");
  }

  @Test
  void groupLabelsAndRedactedEvidenceHaveExplicitExportIntegrity() {
    var now = Instant.now();
    var query =
        new HuntQuery(now.minusSeconds(60), now, null, "", "command_line", "count", 200, null);
    Object result =
        Map.of(
            "query",
            query,
            "groups",
            List.of(Map.of("value", "tool password=\"two word secret\"", "count", 2)));
    assertThat(
            String.valueOf(HuntRedaction.apply(result, new AuthUser(2, "o", "o", Role.OPERATOR))))
        .doesNotContain("two word secret");
    @SuppressWarnings("unchecked")
    var evidence =
        (Map<String, Object>)
            HuntRedaction.apply(
                Map.of(
                    "sha256",
                    "original-hash",
                    "snapshot",
                    Map.of("event_json", "secret raw", "actor", "alice")),
                new AuthUser(1, "v", "v", Role.VIEWER));
    assertThat(evidence).containsEntry("redacted", true).containsEntry("sha256", "original-hash");
    assertThat(evidence.get("exportSha256")).isNotEqualTo("original-hash");
  }
}
