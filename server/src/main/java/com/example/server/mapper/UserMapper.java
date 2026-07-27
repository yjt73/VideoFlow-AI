package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.User;
import org.apache.ibatis.annotations.Mapper;

/** 提供用户表的基础 CRUD 操作。 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
    //MyBatis-Plus自动搞定增删改查
}
