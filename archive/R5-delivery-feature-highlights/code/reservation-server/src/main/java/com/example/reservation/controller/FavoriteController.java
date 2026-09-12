package com.example.reservation.controller;

import com.example.reservation.common.Result;
import com.example.reservation.service.FavoriteService;
import com.example.reservation.vo.FavoriteVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收藏模块控制器（R4 收藏全链路）
 * 路径 /api/favorite/*：学生/管理员登录均可访问；未登录返回 401（拦截器统一校验）
 *
 * @author reservation-team
 */
@Tag(name = "收藏模块", description = "教室收藏/取消收藏（toggle）、我的收藏列表（上限 10 间）")
@RestController
@RequestMapping("/api/favorite")
public class FavoriteController {

    @Resource
    private FavoriteService favoriteService;

    /**
     * 收藏/取消收藏（toggle）：已收藏则取消，未收藏则新增；超过上限（10 间）拒绝并提示
     * 返回 data：true=当前已收藏，false=当前未收藏（便于前端同步按钮状态）
     */
    @Operation(summary = "收藏/取消收藏", description = "toggle：已收藏则取消，未收藏则新增；上限 10 间，超出拒绝")
    @PostMapping("/{classroomId}")
    public Result<Boolean> toggleFavorite(@PathVariable Long classroomId) {
        Boolean favorited = favoriteService.toggleFavorite(classroomId);
        return Result.success(Boolean.TRUE.equals(favorited) ? "收藏成功" : "已取消收藏", favorited);
    }

    /**
     * 我的收藏列表（含教室展示字段，按收藏时间倒序）
     */
    @Operation(summary = "我的收藏列表", description = "我的收藏列表（含教室信息，按收藏时间倒序）")
    @GetMapping("/list")
    public Result<List<FavoriteVO>> listFavorites() {
        return Result.success(favoriteService.listFavorites());
    }
}
