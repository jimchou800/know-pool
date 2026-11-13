package cn.knowpool.system.api;

import cn.knowpool.system.data.ApplicationData;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class SystemController {
    private final ApplicationData data;

    public SystemController(ApplicationData data) {
        this.data = data;
    }

    @GetMapping("/api/v1/system/status")
    public ApplicationData.Status status() {
        return data.status();
    }

    @GetMapping("/api/v1/session")
    public LocalSessionFilter.Session initialize(@RequestAttribute("localSession") LocalSessionFilter.Session session) {
        return session;
    }
}
