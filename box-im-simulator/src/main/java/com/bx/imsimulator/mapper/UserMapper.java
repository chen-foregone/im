package com.bx.imsimulator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bx.imsimulator.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
