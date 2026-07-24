package com.example.demo.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.demo.entity.User;
import com.example.demo.mapper.UserMapper;
import com.example.demo.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Override
    public IPage<User> pageList(int pageNo, int pageSize) {
        Page<User> page = new Page<>(pageNo, pageSize);
        return baseMapper.selectPage(page, null);
    }

    @Override
    public User getByUsername(String username) {
        return lambdaQuery()
            .eq(User::getUsername, username)
            .one();
    }

    @Override
    public boolean existsByUsername(String username) {
        return lambdaQuery()
            .eq(User::getUsername, username)
            .exists();
    }
}
