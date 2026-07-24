package com.example.demo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.example.demo.entity.User;

public interface UserService extends IService<User> {

    IPage<User> pageList(int pageNo, int pageSize);

    User getByUsername(String username);

    boolean existsByUsername(String username);
}
