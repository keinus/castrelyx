package org.castrelyx.manager.hunt.v2;

import java.util.*;
import java.util.regex.Pattern;
import org.castrelyx.manager.auth.*;

public final class HuntRedaction {
  private static final Set<String> PRIVATE_FIELDS = Set.of("command_line", "message");
  private static final String QUOTED_SECRET = "\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+'";
  private static final String UNQUOTED_SECRET = "[^\\s\"',;]+";
  private static final Pattern AUTHORIZATION =
      Pattern.compile(
          "(?i)(\\bauthorization[\"']?[=:\\t ]+)("
              + QUOTED_SECRET
              + "|(?:Bearer|Basic)[\\t ]+"
              + UNQUOTED_SECRET
              + "|"
              + UNQUOTED_SECRET
              + ")");
  private static final Pattern SECRET_ASSIGNMENT =
      Pattern.compile(
          "(?i)(\\b(?:password|passwd|token|secret|api[_-]?key)[\"']?[=:\\t ]+)("
              + QUOTED_SECRET
              + "|"
              + UNQUOTED_SECRET
              + ")");
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private HuntRedaction() {}

  public static Object apply(Object value, AuthUser user) {
    if (user.role() == Role.ADMIN) return value;
    if (value instanceof HuntWorkspace.Resource resource) {
      @SuppressWarnings("unchecked")
      var body = (Map<String, Object>) apply(resource.body(), user);
      return new HuntWorkspace.Resource(
          resource.id(),
          resource.kind(),
          resource.ownerId(),
          resource.shared(),
          resource.title(),
          body,
          resource.version(),
          resource.createdAt(),
          resource.updatedAt(),
          resource.casePermissions());
    }
    if (value instanceof HuntQuery query) {
      var spec = new LinkedHashMap<String, Object>();
      spec.put("from", query.from());
      spec.put("to", query.to());
      spec.put("filter", query.filter());
      spec.put("text", query.text());
      spec.put("groupBy", query.groupBy());
      spec.put("metric", query.metric());
      spec.put("pageSize", query.pageSize());
      spec.put("cursor", query.cursor());
      spec.put("groupFields", query.groupFields());
      spec.put("includeRelations", query.includeRelations());
      return apply(spec, user);
    }
    if (value instanceof HuntQuery.Filter filter) {
      var spec = new LinkedHashMap<String, Object>();
      spec.put("op", filter.op());
      spec.put("field", filter.field());
      spec.put("values", filter.values());
      spec.put("children", filter.children());
      return apply(spec, user);
    }
    if (value instanceof HuntJobs.Job job)
      return new HuntJobs.Job(
          job.id(),
          job.state(),
          job.asOf(),
          job.createdAt(),
          job.error(),
          apply(job.result(), user));
    if (value instanceof HuntJobs.Page page) {
      @SuppressWarnings("unchecked")
      var items = (List<Map<String, Object>>) apply(page.items(), user);
      return new HuntJobs.Page(items, page.nextCursor(), page.hasMore());
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      boolean queryMetadata = map.containsKey("from") && map.containsKey("to");
      map.forEach(
          (key, item) -> {
            String name = String.valueOf(key);
            if (Set.of("event_json", "raw", "raw_log", "snapshotJson", "evidenceJson")
                .contains(name)) return;
            if (queryMetadata
                && name.equals("text")
                && item instanceof String text
                && !text.isBlank()) {
              result.put(name, privateText(text, user));
              return;
            }
            if (name.equals("values")
                && PRIVATE_FIELDS.contains(String.valueOf(map.get("field")))
                && item instanceof List<?> values) {
              result.put(
                  name,
                  values.stream()
                      .map(v -> v instanceof String text ? privateText(text, user) : v)
                      .toList());
              return;
            }
            if (user.role() == Role.VIEWER && Set.of("command_line", "message").contains(name)) {
              result.put(name, "원문 조회 권한 필요");
              return;
            }
            Object sanitized = item;
            if (item instanceof String text && Set.of("command_line", "message").contains(name))
              sanitized = mask(text);
            result.put(name, apply(sanitized, user));
          });
      Object query = map.get("query");
      String group =
          query instanceof HuntQuery q
              ? q.groupBy()
              : query instanceof Map<?, ?> q ? String.valueOf(q.get("groupBy")) : "";
      if (queryMetadata
          && (!Objects.equals(map.get("text"), result.get("text"))
              || !JSON.valueToTree(map.get("filter"))
                  .equals(JSON.valueToTree(result.get("filter"))))) result.put("redacted", true);
      if (Set.of("command_line", "message").contains(group)
          && result.get("groups") instanceof List<?> groups) {
        result.put(
            "groups",
            groups.stream()
                .map(
                    item -> {
                      if (!(item instanceof Map<?, ?> row)) return item;
                      var copy = new LinkedHashMap<String, Object>();
                      row.forEach((k, v) -> copy.put(String.valueOf(k), v));
                      copy.put(
                          "value",
                          user.role() == Role.VIEWER
                              ? "원문 조회 권한 필요"
                              : mask(String.valueOf(copy.get("value"))));
                      return copy;
                    })
                .toList());
      }
      if (map.containsKey("snapshot") && map.containsKey("sha256")) {
        result.put("redacted", true);
        try {
          String snapshotJson =
              new com.fasterxml.jackson.databind.ObjectMapper()
                  .writeValueAsString(result.get("snapshot"));
          result.put("snapshotJson", snapshotJson);
          result.put("exportSha256", HuntWorkspace.hash(snapshotJson));
        } catch (Exception e) {
          throw new IllegalStateException("증거 사본 해시를 계산할 수 없습니다.");
        }
      }
      if (map.containsKey("evidence") && map.containsKey("evidenceSha256")) {
        result.put("redacted", true);
        try {
          String copy = JSON.writeValueAsString(result.get("evidence"));
          result.put("evidenceJson", copy);
          result.put("exportEvidenceSha256", HuntWorkspace.hash(copy));
        } catch (Exception invalid) {
          throw new IllegalStateException("예외 근거 사본 해시를 계산할 수 없습니다.");
        }
      }
      return result;
    }
    if (value instanceof List<?> list) return list.stream().map(item -> apply(item, user)).toList();
    return value;
  }

  private static String privateText(String text, AuthUser user) {
    return user.role() == Role.VIEWER ? "원문 조회 권한 필요" : mask(text);
  }

  private static String mask(String text) {
    return SECRET_ASSIGNMENT
        .matcher(AUTHORIZATION.matcher(text).replaceAll("$1[마스킹]"))
        .replaceAll("$1[마스킹]");
  }
}
