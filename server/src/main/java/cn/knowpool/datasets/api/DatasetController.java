package cn.knowpool.datasets.api;

import cn.knowpool.datasets.Dataset;
import cn.knowpool.datasets.DatasetService;
import cn.knowpool.infrastructure.ApiFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public final class DatasetController {
    private final DatasetService service;

    public DatasetController(DatasetService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/datasets")
    public Page list(HttpServletRequest request) {
        Map<String, String[]> parameters = request.getParameterMap();
        query(parameters, Set.of("limit", "cursor"));
        int limit = 20;
        if (parameters.containsKey("limit")) {
            try {
                String value = parameters.get("limit")[0];
                if (!value.matches("[0-9]+")) throw new NumberFormatException();
                limit = Integer.parseInt(value);
                if (limit < 1 || limit > 100) throw new NumberFormatException();
            } catch (NumberFormatException failure) {
                throw ApiFailure.invalid("limit", "每页数量须为一至一百的整数。");
            }
        }
        String cursor = parameters.containsKey("cursor") ? parameters.get("cursor")[0] : null;
        if (cursor != null && (cursor.isEmpty() || cursor.length() > 2048))
            throw ApiFailure.invalid("cursor", "分页位置长度不正确。");
        DatasetService.Page page = service.list(limit, cursor);
        return new Page(page.items().stream().map(View::of).toList(), page.nextCursor());
    }

    @PostMapping(value = "/api/v1/datasets", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public View create(@RequestBody JsonNode input, HttpServletRequest request) {
        query(request.getParameterMap(), Set.of());
        input(input, true);
        return View.of(service.create(
                text(input, "name", 1, 120, true),
                text(input, "description", 0, 2000, false) == null
                        ? ""
                        : input.get("description").asText()));
    }

    @GetMapping("/api/v1/datasets/{datasetId}")
    public View get(@PathVariable String datasetId, HttpServletRequest request) {
        query(request.getParameterMap(), Set.of());
        return View.of(service.get(datasetId));
    }

    @PatchMapping(value = "/api/v1/datasets/{datasetId}", consumes = "application/json")
    public View update(@PathVariable String datasetId, @RequestBody JsonNode input, HttpServletRequest request) {
        query(request.getParameterMap(), Set.of());
        input(input, false);
        return View.of(service.update(
                datasetId, text(input, "name", 1, 120, false), text(input, "description", 0, 2000, false)));
    }

    private static void query(Map<String, String[]> parameters, Set<String> allowed) {
        parameters.forEach((name, values) -> {
            if (!allowed.contains(name) || values.length != 1) throw ApiFailure.invalid(name, "请求包含未声明或重复的查询参数。");
        });
    }

    private static void input(JsonNode input, boolean create) {
        if (!input.isObject()) throw ApiFailure.invalid(null, "请求正文须为字段对象。");
        for (String field : input.propertyNames()) {
            if (!Set.of("name", "description").contains(field))
                throw ApiFailure.invalid("/" + field.replace("~", "~0").replace("/", "~1"), "请求包含未声明的字段。");
        }
        if (!create && input.isEmpty()) throw ApiFailure.invalid(null, "至少修改名称或说明之一。");
    }

    private static String text(JsonNode input, String field, int minimum, int maximum, boolean required) {
        if (!input.has(field)) {
            if (required) throw ApiFailure.invalid("/" + field, "缺少必填字段。");
            return null;
        }
        JsonNode value = input.get(field);
        if (!value.isString()) throw ApiFailure.invalid("/" + field, "字段须为字符串。");
        String result = value.asText();
        int length = result.codePointCount(0, result.length());
        if (length < minimum || length > maximum) throw ApiFailure.invalid("/" + field, "字段长度不符合要求。");
        return result;
    }

    public record View(
            String id,
            String name,
            String description,
            String createdAt,
            String updatedAt,
            long fileCount,
            long totalBytes) {
        static View of(Dataset dataset) {
            return new View(
                    dataset.id(),
                    dataset.name(),
                    dataset.description(),
                    dataset.createdAt(),
                    dataset.updatedAt(),
                    0,
                    0);
        }
    }

    public record Page(List<View> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
