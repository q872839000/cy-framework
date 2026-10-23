package com.cy.system.user.dao;

import com.cy.system.user.entity.SysUser;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 用户数据操作层
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 查询所有用户（包括已删除的）
     * 用于初始化用户名称缓存，供查询业务数据时显示创建人/修改人名称
     */
    @Select("SELECT id, nickname FROM sys_user")
    List<SysUser> selectAllIncludeDeleted();
}
