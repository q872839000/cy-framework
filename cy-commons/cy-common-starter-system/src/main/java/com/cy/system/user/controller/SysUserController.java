package com.cy.system.user.controller;

import com.cy.core.base.R;
import com.cy.log.annotation.AccessLog;
import com.cy.log.annotation.SysLog;
import com.cy.system.user.entity.SysUser;
import com.cy.system.user.entity.condition.SysUserCondition;
import com.cy.system.user.service.SysUserService;
import com.cy.system.user.vo.AssignRolesRequest;
import com.cy.system.user.vo.UpdatePasswordRequest;
import com.cy.userfill.annotation.FillUserName;
import com.mybatisflex.core.paginate.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 用户管理控制器
 */
@RestController
@RequestMapping("/system/user")
@Validated
@Tag(name = "用户管理")
@AccessLog(retCharLimit = 2048)
public class SysUserController {

    private final SysUserService userService;

    public SysUserController(SysUserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "创建用户")
    @PostMapping("/create")
    @SysLog(module = "用户管理", operation = "创建用户", targetType = "User")
    public R<Boolean> create(@RequestBody SysUser user) {
        return R.ok(userService.createUser(user));
    }

    @Operation(summary = "更新用户")
    @PostMapping("/update")
    @SysLog(module = "用户管理", operation = "更新用户", targetType = "User")
    public R<Boolean> update(@RequestBody SysUser user) {
        return R.ok(userService.updateUser(user));
    }

    @Operation(summary = "查询用户详情")
    @GetMapping("/get-info")
    public R<SysUser> getInfo(@Parameter(description = "用户ID") @RequestParam Long id) {
        SysUser user = userService.getUserById(id);
        if (user != null) {
            user.setPassword(null); // 不返回密码
        }
        return R.ok(user);
    }

    @Operation(summary = "查询所有用户")
    @GetMapping("/list-all")
    public R<List<SysUser>> listAll() {
        List<SysUser> users = userService.listAll();
        users.forEach(user -> user.setPassword(null)); // 不返回密码
        return R.ok(users);
    }

    @Operation(summary = "删除用户")
    @PostMapping("/remove")
    @SysLog(module = "用户管理", operation = "删除用户", targetType = "User", targetIdEl = "#id")
    public R<Boolean> remove(@Parameter(description = "用户ID") @RequestParam Long id) {
        return R.ok(userService.deleteUser(id));
    }

    @Operation(summary = "修改用户状态")
    @PostMapping("/update-status")
    @SysLog(module = "用户管理", operation = "修改用户状态", targetType = "User", targetIdEl = "#id")
    public R<Boolean> updateStatus(
            @Parameter(description = "用户ID") @RequestParam Long id,
            @Parameter(description = "状态：0-禁用，1-正常") @RequestParam Integer status) {
        return R.ok(userService.updateStatus(id, status));
    }

    @Operation(summary = "修改密码")
    @PostMapping("/update-password")
    @SysLog(module = "用户管理", operation = "修改密码", targetType = "User", targetIdEl = "#request.userId")
    public R<Boolean> updatePassword(@Valid @RequestBody UpdatePasswordRequest request) {
        return R.ok(userService.updatePassword(request.getUserId(), request.getOldPassword(), request.getNewPassword()));
    }

    @Operation(summary = "重置密码")
    @PostMapping("/reset-password")
    @SysLog(module = "用户管理", operation = "重置密码", targetType = "User", targetIdEl = "#id")
    public R<Boolean> resetPassword(
            @Parameter(description = "用户ID") @RequestParam Long id,
            @Parameter(description = "新密码") @RequestParam String newPassword) {
        return R.ok(userService.resetPassword(id, newPassword));
    }

    @Operation(summary = "分配角色给用户")
    @PostMapping("/assign-roles")
    @SysLog(module = "用户管理", operation = "分配角色", targetType = "User", targetIdEl = "#request.userId")
    public R<Boolean> assignRoles(@Valid @RequestBody AssignRolesRequest request) {
        return R.ok(userService.assignRoles(request.getUserId(), request.getRoleIds()));
    }

    @Operation(summary = "获取用户的角色ID列表")
    @GetMapping("/role-ids")
    public R<List<Long>> getUserRoleIds(@Parameter(description = "用户ID") @RequestParam Long userId) {
        return R.ok(userService.getUserRoleIds(userId));
    }

    @Operation(summary = "分页查询用户", description = "支持用户名/昵称模糊查询和状态筛选")
    @GetMapping("/page")
    @FillUserName
    public R<Page<SysUser>> pageUsers(SysUserCondition condition) {
        Page<SysUser> page = userService.pageUsers(condition);
        page.getRecords().forEach(user -> user.setPassword(null));
        return R.ok(page);
    }

    @Operation(summary = "查询所有活跃用户")
    @GetMapping("/list-all-active")
    public R<List<SysUser>> listAllActive(String name) {
        List<SysUser> users = userService.getAllActiveUsers(name);
        users.forEach(user -> user.setPassword(null)); // 不返回密码
        return R.ok(users);
    }
}
