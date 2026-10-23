package com.cy.system.file.service;

import com.cy.system.file.entity.SysFile;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;
import lombok.Data;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/7
 * @Description: 系统文件服务接口
 */
public interface SysFileService extends IService<SysFile> {

    /**
     * 创建文件记录
     *
     * @param file 文件实体
     * @return 是否创建成功
     */
    Boolean createFile(SysFile file);

    /**
     * 更新文件记录
     *
     * @param file 文件实体
     * @return 是否更新成功
     */
    Boolean updateFile(SysFile file);

    /**
     * 根据ID查询文件
     *
     * @param id 文件ID
     * @return 文件实体
     */
    SysFile getFileById(Long id);

    /**
     * 根据MD5查询
     * @param md5Hash md5值
     * @return 返回文件信息
     */
    SysFile getFileByMd5(String md5Hash);

    /**
     * 根据文件路径查询文件
     *
     * @param filePath 文件路径
     * @return 文件实体
     */
    SysFile getFileByPath(String filePath);

    /**
     * 查询所有文件
     *
     * @return 文件列表
     */
    List<SysFile> listAll();

    /**
     * 分页查询文件
     *
     * @param page     分页信息
     * @param fileName 文件名（可选）
     * @param fileType 文件类型（可选）
     * @param status   文件状态（可选）
     * @return 分页结果
     */
    Page<SysFile> pageFiles(Page<SysFile> page, String fileName, Integer fileType, Integer status);

    /**
     * 根据ID删除文件
     *
     * @param id 文件ID
     * @return 是否删除成功
     */
    Boolean deleteFile(Long id);

    /**
     * 批量删除文件
     *
     * @param ids 文件ID列表
     * @return 是否删除成功
     */
    Boolean deleteFiles(List<Long> ids);


    /**
     * 根据父文件ID查询子文件列表
     *
     * @param parentFileId 父文件ID
     * @return 子文件列表
     */
    List<SysFile> listChildFiles(Long parentFileId);

    /**
     * 批量创建文件记录
     *
     * @param files 文件实体列表
     * @return 是否创建成功
     */
    Boolean createFileBatch(List<SysFile> files);

    /**
     * 根据MD5列表查询文件
     *
     * @param md5List MD5哈希值列表
     * @return 文件列表
     */
    List<SysFile> listFilesByMd5List(List<String> md5List);

}
