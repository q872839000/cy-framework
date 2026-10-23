package com.cy.system.file.dao;

import com.cy.system.file.entity.SysFile;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Auther: pcy
 * @Date: 2026/1/7
 * @Description: 针对表【sys_file(系统文件信息表)】的数据库操作Mapper
 */
@Mapper
public interface SysFileMapper extends BaseMapper<SysFile> {
}
