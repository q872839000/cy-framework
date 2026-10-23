package com.cy.system.user.service.impl;

import com.cy.system.user.dao.SysUserRoleMapper;
import com.cy.system.user.entity.SysUserRole;
import com.cy.system.user.service.SysUserRoleService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 用户角色关联服务实现类
 */
@Service
public class SysUserRoleServiceImpl extends ServiceImpl<SysUserRoleMapper, SysUserRole> implements SysUserRoleService {

    @Override
    public void deleteByUserId(Long userId) {
        this.remove(QueryWrapper.create()
                .eq(SysUserRole::getUserId,userId));
    }

    @Override
    @Transactional
    public void saveUserRoles(Long userId, List<Long> roleIds) {
        // 先删除原有关联
        deleteByUserId(userId);
        // 添加新的关联
        if (roleIds != null && !roleIds.isEmpty()) {
            for (Long roleId : roleIds) {
                SysUserRole userRole = new SysUserRole();
                userRole.setUserId(userId);
                userRole.setRoleId(roleId);
                this.save(userRole);
            }
        }
    }

    @Override
    public List<Long> getRoleIdsByUserId(Long userId) {
        QueryWrapper query = QueryWrapper.create()
                .select(SysUserRole::getRoleId)
                .where(SysUserRole::getUserId).eq(userId);
        List<SysUserRole> userRoles = this.list(query);
        return userRoles.stream().map(SysUserRole::getRoleId).toList();
    }
}
