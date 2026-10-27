package com.hope.xueling.common.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hope.xueling.common.domain.dto.UserDTO;
import com.hope.xueling.common.domain.entity.User;
import com.hope.xueling.common.domain.vo.UserVO;
import com.hope.xueling.common.exception.BusinessException;
import com.hope.xueling.common.exception.ValidationException;
import com.hope.xueling.common.mapper.UserMapper;
import com.hope.xueling.common.service.IUserService;
import com.hope.xueling.common.util.AliOSSUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 用户服务实现类
 * @author 谢光益
 * @since 2026/1/20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements IUserService {

    private final UserMapper userMapper;
    private final AliOSSUtils aliOSSUtils;
    /**
     * 新用户默认头像地址
     */
    private static final String DEFAULT_AVATAR_URL = "https://xueling-platform.oss-cn-hangzhou.aliyuncs.com/user/avatar/default/d915734b-a240-4350-acb6-30b6e2d3d981.jpg";

    /**
     * 新增用户：生成唯一用户名并填充默认头像，主键由 MyBatis-Plus 雪花算法生成
     * @param user 待落库的用户，至少包含邮箱或手机号与密码摘要
     */
    @Override
    public void insertUser(User user) {
        // 主键由 MyBatis-Plus 统一生成（见 User#id 上的 @TableId），此处不再手动赋值，
        // 避免应用层雪花ID与数据库 AUTO_INCREMENT 两套策略并存。
        user.setUsername(generateUniqueUsername());
        user.setAvatarUrl(DEFAULT_AVATAR_URL);
        userMapper.insert(user);
    }

    @Override
    public User getUserByEmailWithPassword(String email) {
        //邮箱不能为空
        if (email == null) {
            throw new ValidationException("邮箱不能为空");
        }
        //正则表达式校验
        if (!email.matches("^[a-zA-Z0-9_.-]+@[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z0-9]{2,6}$")) {
            throw new ValidationException("邮箱格式错误");
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email", email);
        return userMapper.selectOne(queryWrapper);
    }

    /**
     * 根据用户手机号查用户信息
     * @param phone 手机号
     * @return User 用户实体类
     */
    @Override
    public User getUserByPhoneWithPassword(String phone) {
        //手机号不能为空
        if (phone == null) {
            throw new ValidationException("手机号不能为空");
        }
        //正则表达式校验
        if (!phone.matches("^1[3456789]\\d{9}$")) {
            throw new ValidationException("手机号格式错误");
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("phone", phone);
        return userMapper.selectOne(queryWrapper);
    }


    /**
     * 按用户 ID 更新资料，仅允许修改用户名、性别、生日与个人简介
     * @param userDTO 用户资料，id 为字符串形式
     */
    @Override
    public void updateUserById(UserDTO userDTO) {
        // DTO 的 id 是对外的字符串形式，这里转回 Long 参与数据库操作
        Long userId = parseUserId(userDTO.getId());
        UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", userId);
        if (userDTO.getBio() != null) {
            if (userDTO.getBio().length() > 500) {
                throw new ValidationException("个人简介不能超过500字");
            }
            updateWrapper.set("bio", userDTO.getBio());
        }
        if (userDTO.getBirthday() != null) {
            updateWrapper.set("birthday", userDTO.getBirthday());
        }
        if (userDTO.getGender() != null) {
            updateWrapper.set("gender", userDTO.getGender());
        }
        if (userDTO.getUsername() != null) {
            if (userDTO.getUsername().length() > 20) {
                throw new ValidationException("用户名不能超过20字");
            }
            updateWrapper.set("username", userDTO.getUsername());
        }
        // 传 null 而不是实体对象：避免 BeanUtils 拷贝进来的 email/phone/avatarUrl 等
        // 非空字段被 MyBatis-Plus 自动拼进 SET 子句（那些字段不允许通过该接口修改）
        userMapper.update(null, updateWrapper);
    }


    /**
     * 查询用户信息并转换为视图对象，会员等级按有效期实时判定
     * @param id 用户 ID
     * @return 用户视图
     */
    @Override
    public UserVO getUserInfo(Long id) {
        //检查id是否为空
        if (id == null) {
            throw new ValidationException("用户ID不能为空");
        }
        //根据用户ID查询用户信息
        User user = userMapper.selectById(id);
        if (user != null) {
            //将用户实体类转换为用户视图对象
            UserVO userVO = new UserVO();
            BeanUtils.copyProperties(user, userVO);
            // 实体 id 是 Long、VO id 是 String，类型不一致时 BeanUtils 会静默跳过，必须显式转换
            userVO.setId(String.valueOf(user.getId()));
            // 对外展示的会员等级与服务端权限判断保持一致，过期或日期缺失均视为普通用户。
            if (userMapper.isVip(id) == null) userVO.setVipLevel(0);
            return userVO;
        }
        //用户不存在异常
        throw new BusinessException("用户不存在");
    }

    /**
     * 根据邮箱和手机号获取密码
     * @param email 邮箱
     * @return String 密码
     */
    @Override
    public String getPasswordByEmail(String email) {
        //检查邮箱是否为空
        if (email == null) {
            //邮箱不能为空异常
            throw new ValidationException("邮箱不能为空");
        }
        //根据邮箱查询用户密码
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email", email);
        String password = userMapper.selectOne(queryWrapper).getPassword();
        //如果密码为空，则抛出异常
        if (password == null) {
            //邮箱或手机号错误异常
            throw new BusinessException("不存在该邮箱");
        }
        return password;
    }

    /**
     * 根据手机号获取密码
     * @param phone 手机号
     * @return String 密码
     */
    @Override
    public String getPasswordByPhone(String phone) {
        //检查手机号是否为空
        if (phone == null) {
            //手机号不能为空异常
            throw new ValidationException("手机号不能为空");
        }
        //根据手机号查询用户密码
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("phone", phone);
        String password = userMapper.selectOne(queryWrapper).getPassword();
        //如果密码为空，则抛出异常
        if (password == null) {
            //邮箱或手机号错误异常
            throw new BusinessException("不存在该手机号");
        }
        return password;
    }

    /**
     * 上传头像到 OSS 并回写用户的头像地址
     * @param userId 用户 ID
     * @param avatar 头像文件
     * @return 头像访问地址
     */
    @Override
    public String uploadAvatar(Long userId, MultipartFile avatar) {
        try {
            String avatarUrl = aliOSSUtils.uploadAvatar(avatar, userId);
            //更新用户头像URL
            UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userId).set("avatar_url", avatarUrl);
            userMapper.update(null, updateWrapper);
            return avatarUrl;
        } catch (IOException e) {
            log.error("用户{}头像上传失败",userId,e);
            throw new BusinessException("头像上传失败,请稍后重试");
        }
    }

    /**
     * 注销用户：将账号状态置为已注销（3），手机验证码校验尚未实现
     * @param userId 用户 ID
     * @param verificationCode 验证码
     */
    @Override
    public void cancelUserAccount(Long userId, String verificationCode) {
        //检查用户ID是否为空
        if (userId == null) {
            //用户ID不能为空异常
            throw new ValidationException("用户ID不能为空");
        }

        //TODO 手机验证码校验

        //TODO 注销用户账号
        //逻辑删除用户
        userMapper.updateAccountStatusById(userId);
    }

    /**
     * 查询账号状态
     * @param userId 用户 ID
     * @return 账号状态：0 禁用、1 正常、3 已注销；用户不存在时返回 null
     */
    @Override
    public Integer getAccountStatus(Long userId) {
        return userMapper.selectAccountStatus(userId);
    }

    /**
     * 生成唯一用户名
     * <p>原实现使用“用户总数+1”作为序号，并发注册或存在注销用户时会与
     * username 唯一索引冲突。改为取雪花ID后6位，并在落库前查重。</p>
     * @return 未被占用的用户名
     */
    private String generateUniqueUsername() {
        for (int i = 0; i < 5; i++) {
            String snowflakeId = IdUtil.getSnowflakeNextIdStr();
            String username = "学灵" + snowflakeId.substring(snowflakeId.length() - 6);
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("username", username);
            if (userMapper.selectCount(queryWrapper) == 0) {
                return username;
            }
        }
        // 极端情况下（连续5次撞名）退回完整雪花ID，保证唯一
        return "学灵" + IdUtil.getSnowflakeNextIdStr();
    }

    /**
     * 将对外的字符串用户ID转换为 Long
     * @param id 字符串形式的用户ID
     * @return Long 用户ID
     */
    private Long parseUserId(String id) {
        if (id == null || id.isBlank()) {
            throw new ValidationException("用户ID不能为空");
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ValidationException("用户ID格式错误");
        }
    }


}
