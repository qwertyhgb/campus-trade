package com.ming.campustrade.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ming.campustrade.entity.Favorite;

@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {
    
}
