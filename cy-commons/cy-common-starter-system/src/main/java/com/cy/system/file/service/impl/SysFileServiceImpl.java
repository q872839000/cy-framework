package com.cy.system.file.service.impl;

import com.cy.core.exception.CYException;
import com.cy.system.file.dao.SysFileMapper;
import com.cy.system.file.entity.SysFile;
import com.cy.system.file.service.SysFileService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


/**
 * @Auther: pcy
 * @Date: 2026/1/7
 * @Description: 系统文件服务实现类
 */
@Service
@RequiredArgsConstructor
public class SysFileServiceImpl extends ServiceImpl<SysFileMapper, SysFile> implements SysFileService {

    @Override
    @Transactional
    public Boolean createFile(SysFile file) {
        // 检查MD5是否已存在
        if (StringUtils.isNotBlank(file.getMd5Hash())) {
            SysFile existFile = getFileByMd5(file.getMd5Hash());
            if (existFile != null) {
                throw new CYException(20001); // 文件已存在
            }
        }
        
        if (file.getUnzipped() == null) {
            file.setUnzipped(0); // 未解压
        }
        
        return this.save(file);
    }

    @Override
    public Boolean updateFile(SysFile file) {
        return this.updateById(file);
    }

    @Override
    public SysFile getFileById(Long id) {
        return this.getById(id);
    }

    @Override
    public SysFile getFileByMd5(String md5Hash) {
        if (StringUtils.isBlank(md5Hash)) {
            return null;
        }
        QueryWrapper query = QueryWrapper.create()
                .where(SysFile::getMd5Hash).eq(md5Hash); // 排除已删除的文件
        return this.getOne(query);
    }

    @Override
    public SysFile getFileByPath(String filePath) {
        if (StringUtils.isBlank(filePath)) {
            return null;
        }
        QueryWrapper query = QueryWrapper.create()
                .where(SysFile::getFilePath).eq(filePath); // 排除已删除的文件
        return this.getOne(query);
    }

    @Override
    public List<SysFile> listAll() {
        QueryWrapper query = QueryWrapper.create()
                .orderBy(SysFile::getCreateTime).desc();
        return this.list(query);
    }

    @Override
    public Page<SysFile> pageFiles(Page<SysFile> page, String fileName, Integer fileType, Integer status) {
        QueryWrapper query = QueryWrapper.create();
        
        query.orderBy(SysFile::getCreateTime).desc();
        
        return this.page(page, query);
    }

    @Override
    @Transactional
    public Boolean deleteFile(Long id) {
        // 软删除：将状态设置为0
        SysFile updateFile = new SysFile();
        updateFile.setId(id);
        return this.updateById(updateFile);
    }

    @Override
    @Transactional
    public Boolean deleteFiles(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return true;
        }
        for (Long id : ids) {
            deleteFile(id);
        }
        return true;
    }


    @Override
    public List<SysFile> listChildFiles(Long parentFileId) {
        if (parentFileId == null) {
            return List.of();
        }
        
        QueryWrapper query = QueryWrapper.create()
                .where(SysFile::getParentFileId).eq(parentFileId)
                .orderBy(SysFile::getCreateTime).asc();
        
        return this.list(query);
    }

    @Override
    @Transactional
    public Boolean createFileBatch(List<SysFile> files) {
        if (files == null || files.isEmpty()) {
            return true;
        }
        for (SysFile file : files) {
            if (file.getUnzipped() == null) {
                file.setUnzipped(0);
            }
        }
        return this.saveBatch(files);
    }

    @Override
    public List<SysFile> listFilesByMd5List(List<String> md5List) {
        if (md5List == null || md5List.isEmpty()) {
            return List.of();
        }
        QueryWrapper query = QueryWrapper.create()
                .where(SysFile::getMd5Hash).in(md5List);
        return this.list(query);
    }
}
