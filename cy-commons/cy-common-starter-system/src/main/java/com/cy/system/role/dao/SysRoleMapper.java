package com.cy.system.role.dao;

import com.cy.system.role.entity.SysRole;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 角色数据操作层
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
}
