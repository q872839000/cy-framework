package com.cy.system.menu.dao;

import com.cy.system.menu.entity.SysMenu;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 菜单数据操作层
 */
@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {
}
