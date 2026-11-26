package ru.mirea.vinylstore.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Точные маршруты React SPA; неизвестные URL и REST API сохраняют свои HTTP-ошибки. */
@Controller
public class FrontendController {
    @GetMapping({"/catalog", "/favorites", "/cart", "/account", "/orders", "/admin", "/albums/{id}"})
    public String frontend() {
        return "forward:/index.html";
    }
}
