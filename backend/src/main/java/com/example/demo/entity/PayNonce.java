package com.example.demo.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pay_nonce")
public class PayNonce {

    @TableId
    private String nonce;

    private LocalDateTime usedAt;
}