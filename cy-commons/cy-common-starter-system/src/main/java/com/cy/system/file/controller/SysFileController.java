package com.cy.system.file.controller;

import com.cy.core.base.R;
import com.cy.system.file.entity.SysFile;
import com.cy.system.file.service.SysFileService;
import com.cy.system.file.vo.FileQueryRequest;
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
 * @Date: 2026/1/7
 * @Description: 文件管理控制器
 */
@RestController
@RequestMapping("/system/file")
@Validated
@Tag(name = "文件管理")
public class SysFileController {

    private final SysFileService fileService;

    public SysFileController(SysFileService fileService) {
        this.fileService = fileService;
    }

    @Operation(summary = "创建文件记录")
    @PostMapping("/create")
    public R<Boolean> create(@Valid @RequestBody SysFile file) {
        return R.ok(fileService.createFile(file));
    }

    @Operation(summary = "更新文件记录")
    @PostMapping("/update")
    public R<Boolean> update(@RequestBody SysFile file) {
        return R.ok(fileService.updateFile(file));
    }

    @Operation(summary = "查询文件详情")
    @GetMapping("/get-info")
    public R<SysFile> getInfo(@Parameter(description = "文件ID") @RequestParam Long id) {
        SysFile file = fileService.getFileById(id);
        return R.ok(file);
    }


    @Operation(summary = "查询所有文件")
    @GetMapping("/list-all")
    public R<List<SysFile>> listAll() {
        List<SysFile> files = fileService.listAll();
        return R.ok(files);
    }

    @Operation(summary = "分页查询文件")
    @PostMapping("/page")
    public R<Page<SysFile>> page(@Valid @RequestBody FileQueryRequest request) {
        Page<SysFile> page = new Page<>(request.getPageNumber(), request.getPageSize());
        Page<SysFile> result = fileService.pageFiles(page, request.getFileName(), 
                request.getFileType(), request.getStatus());
        return R.ok(result);
    }

    @Operation(summary = "删除文件")
    @PostMapping("/remove")
    public R<Boolean> remove(@Parameter(description = "文件ID") @RequestParam Long id) {
        return R.ok(fileService.deleteFile(id));
    }

    @Operation(summary = "批量删除文件")
    @PostMapping("/batch-remove")
    public R<Boolean> batchRemove(@Parameter(description = "文件ID列表") @RequestBody List<Long> ids) {
        return R.ok(fileService.deleteFiles(ids));
    }


    @Operation(summary = "查询子文件列表")
    @GetMapping("/list-children")
    public R<List<SysFile>> listChildren(@Parameter(description = "父文件ID") @RequestParam Long parentFileId) {
        List<SysFile> children = fileService.listChildFiles(parentFileId);
        return R.ok(children);
    }

}
