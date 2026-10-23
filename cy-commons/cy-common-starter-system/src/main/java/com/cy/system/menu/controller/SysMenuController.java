package com.cy.system.menu.controller;

import com.cy.core.base.R;
import com.cy.log.annotation.SysLog;
import com.cy.system.menu.entity.SysMenu;
import com.cy.system.menu.service.SysMenuService;
import com.cy.system.menu.vo.MenuTreeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 菜单管理控制器
 */
@RestController
@RequestMapping("/system/menu")
@Validated
@Tag(name = "菜单管理")
public class SysMenuController {

    private final SysMenuService menuService;

    public SysMenuController(SysMenuService menuService) {
        this.menuService = menuService;
    }

    @Operation(summary = "创建菜单")
    @PostMapping("/create")
    @SysLog(module = "菜单管理", operation = "创建菜单", targetType = "Menu")
    public R<Boolean> create(@RequestBody SysMenu menu) {
        return R.ok(menuService.createMenu(menu));
    }

    @Operation(summary = "更新菜单")
    @PostMapping("/update")
    @SysLog(module = "菜单管理", operation = "更新菜单", targetType = "Menu")
    public R<Boolean> update(@RequestBody SysMenu menu) {
        return R.ok(menuService.updateMenu(menu));
    }

    @Operation(summary = "查询菜单详情")
    @GetMapping("/get-info")
    public R<SysMenu> getInfo(@Parameter(description = "菜单ID") @RequestParam Long id) {
        return R.ok(menuService.getMenuById(id));
    }

    @Operation(summary = "查询所有菜单")
    @GetMapping("/list-all")
    public R<List<SysMenu>> listAll() {
        return R.ok(menuService.listAll());
    }

    @Operation(summary = "获取菜单树形结构")
    @GetMapping("/tree")
    public R<List<MenuTreeVO>> getMenuTree() {
        return R.ok(menuService.getMenuTree());
    }

    @Operation(summary = "删除菜单")
    @PostMapping("/remove")
    @SysLog(module = "菜单管理", operation = "删除菜单", targetType = "Menu", targetIdEl = "#id")
    public R<Boolean> remove(@Parameter(description = "菜单ID") @RequestParam Long id) {
        return R.ok(menuService.deleteMenu(id));
    }

    @Operation(summary = "修改菜单状态")
    @PostMapping("/update-status")
    @SysLog(module = "菜单管理", operation = "修改菜单状态", targetType = "Menu", targetIdEl = "#id")
    public R<Boolean> updateStatus(
            @Parameter(description = "菜单ID") @RequestParam Long id,
            @Parameter(description = "状态：0-禁用，1-正常") @RequestParam Integer status) {
        return R.ok(menuService.updateStatus(id, status));
    }
}
