package com.example.reservation.service;

import com.example.reservation.vo.FavoriteVO;

import java.util.List;

/**
 * 收藏业务接口（R4 收藏全链路）
 *
 * @author reservation-team
 */
public interface FavoriteService {

    /**
     * 收藏/取消收藏（toggle）：已收藏则取消，未收藏则新增；
     * 新增前校验上限（每人最多 10 间，需求文档 1.4）
     *
     * @return true=当前已收藏（本次新增），false=当前未收藏（本次取消）
     */
    Boolean toggleFavorite(Long classroomId);

    /**
     * 我的收藏列表（含教室展示字段，按收藏时间倒序；上限 10 间）
     */
    List<FavoriteVO> listFavorites();
}
