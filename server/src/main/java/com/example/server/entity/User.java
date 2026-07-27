package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 系统用户，对应 users 数据表。 */
@Data
@TableName("users")
public class User {

    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用于登录且保持唯一的账号名。 */
    private String username;

    /** 当前实现保存的登录密码。 */
    private String password;

    /** 页面展示名称。 */
    private String nickname;

    /** 用户头像地址。 */
    private String avatar;

    /** 用户权限角色。 */
    private String role;

    // 彻底删除了 createTime，防止 MyBatis 因为找不到配置而报错
    // 数据库会自动填入当前时间，放心
}
