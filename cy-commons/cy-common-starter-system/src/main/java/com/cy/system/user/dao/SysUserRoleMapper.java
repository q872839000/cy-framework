package com.cy.system.user.dao;

import com.cy.system.user.entity.SysUserRole;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 用户角色关联数据操作层
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
}
