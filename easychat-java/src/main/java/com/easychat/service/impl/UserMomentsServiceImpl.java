package com.easychat.service.impl;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.PageSize;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.UserMoments;
import com.easychat.entity.po.UserMomentsComment;
import com.easychat.entity.po.UserMomentsLike;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.*;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.UserMomentsCommentMapper;
import com.easychat.mappers.UserMomentsLikeMapper;
import com.easychat.mappers.UserMomentsMapper;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.service.UserMomentsService;
import com.easychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;


/**
 * 朋友圈动态表 业务接口实现
 */
@Service("userMomentsService")
public class UserMomentsServiceImpl implements UserMomentsService {

    private static final Logger logger = LoggerFactory.getLogger(UserMomentsServiceImpl.class);

    @Resource
    private UserMomentsMapper<UserMoments, UserMomentsQuery> userMomentsMapper;

    @Resource
    private UserMomentsCommentMapper<UserMomentsComment, UserMomentsCommentQuery> userMomentsCommentMapper;

    @Resource
    private UserMomentsLikeMapper<UserMomentsLike, UserMomentsLikeQuery> userMomentsLikeMapper;

    @Resource
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Resource
    private RedisComponet redisComponet;

    /**
     * 根据条件查询列表
     */
    @Override
    public List<UserMoments> findListByParam(UserMomentsQuery param) {
        return this.userMomentsMapper.selectList(param);
    }

    /**
     * 根据条件查询列表
     */
    @Override
    public Integer findCountByParam(UserMomentsQuery param) {
        return this.userMomentsMapper.selectCount(param);
    }

    /**
     * 分页查询方法
     */
    @Override
    public PaginationResultVO<UserMoments> findListByPage(UserMomentsQuery param) {
        int count = this.findCountByParam(param);
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        param.setSimplePage(page);
        List<UserMoments> list = this.findListByParam(param);
        PaginationResultVO<UserMoments> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
        return result;
    }

    /**
     * 新增
     */
    @Override
    public Integer add(UserMoments bean) {
        return this.userMomentsMapper.insert(bean);
    }

    /**
     * 批量新增
     */
    @Override
    public Integer addBatch(List<UserMoments> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.userMomentsMapper.insertBatch(listBean);
    }

    /**
     * 批量新增或者修改
     */
    @Override
    public Integer addOrUpdateBatch(List<UserMoments> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.userMomentsMapper.insertOrUpdateBatch(listBean);
    }

    /**
     * 多条件更新
     */
    @Override
    public Integer updateByParam(UserMoments bean, UserMomentsQuery param) {
        StringTools.checkParam(param);
        return this.userMomentsMapper.updateByParam(bean, param);
    }

    /**
     * 多条件删除
     */
    @Override
    public Integer deleteByParam(UserMomentsQuery param) {
        StringTools.checkParam(param);
        return this.userMomentsMapper.deleteByParam(param);
    }

    /**
     * 根据MomentsId获取对象
     */
    @Override
    public UserMoments getUserMomentsByMomentsId(Long momentsId) {
        return this.userMomentsMapper.selectByMomentsId(momentsId);
    }

    /**
     * 根据MomentsId修改
     */
    @Override
    public Integer updateUserMomentsByMomentsId(UserMoments bean, Long momentsId) {
        return this.userMomentsMapper.updateByMomentsId(bean, momentsId);
    }

    /**
     * 根据MomentsId删除
     */
    @Override
    public Integer deleteUserMomentsByMomentsId(Long momentsId) {
        return this.userMomentsMapper.deleteByMomentsId(momentsId);
    }

    /**
     * 发布朋友圈动态
     */
    @Override
    public UserMoments publishMoments(TokenUserInfoDto tokenUserInfoDto, String content, String images, Integer type) {
        if (StringTools.isEmpty(content) && StringTools.isEmpty(images)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        UserMoments moments = new UserMoments();
        moments.setUserId(tokenUserInfoDto.getUserId());
        moments.setNickName(tokenUserInfoDto.getNickName());
        moments.setContent(content);
        moments.setImages(images);
        moments.setType(type == null ? 0 : type);
        moments.setLikeCount(0);
        moments.setCommentCount(0);
        moments.setCreateTime(System.currentTimeMillis());
        moments.setStatus(1);
        this.userMomentsMapper.insert(moments);
        return moments;
    }

    /**
     * 获取朋友圈动态流(好友+自己的动态)
     */
    @Override
    public PaginationResultVO<UserMoments> loadMomentsFeed(TokenUserInfoDto tokenUserInfoDto, Integer pageNo) {
        String userId = tokenUserInfoDto.getUserId();
        // 获取好友列表
        List<String> contactList = redisComponet.getUserContactList(userId);
        // 包含自己
        List<String> userIdList = new ArrayList<>(contactList);
        userIdList.add(userId);

        UserMomentsQuery query = new UserMomentsQuery();
        query.setUserIdList(userIdList);
        query.setStatus(1);
        query.setOrderBy("create_time desc");
        query.setPageNo(pageNo == null ? 1 : pageNo);
        query.setPageSize(PageSize.SIZE15.getSize());

        PaginationResultVO<UserMoments> result = this.findListByPage(query);
        // 填充点赞状态和评论/点赞列表
        if (result.getList() != null) {
            for (UserMoments moments : result.getList()) {
                fillMomentsDetail(moments, userId);
            }
        }
        return result;
    }

    /**
     * 获取指定用户的动态列表
     */
    @Override
    public PaginationResultVO<UserMoments> loadUserMoments(String userId, String currentUserId, Integer pageNo) {
        UserMomentsQuery query = new UserMomentsQuery();
        query.setUserId(userId);
        query.setStatus(1);
        query.setOrderBy("create_time desc");
        query.setPageNo(pageNo == null ? 1 : pageNo);
        query.setPageSize(PageSize.SIZE15.getSize());

        PaginationResultVO<UserMoments> result = this.findListByPage(query);
        if (result.getList() != null) {
            for (UserMoments moments : result.getList()) {
                fillMomentsDetail(moments, currentUserId);
            }
        }
        return result;
    }

    /**
     * 删除自己的动态(软删除)
     */
    @Override
    public void deleteMoments(String userId, Long momentsId) {
        UserMoments moments = userMomentsMapper.selectByMomentsId(momentsId);
        if (moments == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (!moments.getUserId().equals(userId)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        UserMoments updateBean = new UserMoments();
        updateBean.setStatus(0);
        userMomentsMapper.updateByMomentsId(updateBean, momentsId);
    }

    /**
     * 点赞/取消点赞
     */
    @Override
    public void likeMoments(TokenUserInfoDto tokenUserInfoDto, Long momentsId) {
        UserMoments moments = userMomentsMapper.selectByMomentsId(momentsId);
        if (moments == null || moments.getStatus() != 1) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        String userId = tokenUserInfoDto.getUserId();
        UserMomentsLike existingLike = userMomentsLikeMapper.selectByMomentsIdAndUserId(momentsId, userId);
        if (existingLike != null) {
            // 取消点赞
            userMomentsLikeMapper.deleteByMomentsIdAndUserId(momentsId, userId);
            userMomentsMapper.incrementLikeCount(momentsId, -1);
        } else {
            // 点赞
            UserMomentsLike like = new UserMomentsLike();
            like.setMomentsId(momentsId);
            like.setUserId(userId);
            like.setNickName(tokenUserInfoDto.getNickName());
            like.setCreateTime(System.currentTimeMillis());
            userMomentsLikeMapper.insert(like);
            userMomentsMapper.incrementLikeCount(momentsId, 1);
        }
    }

    /**
     * 评论动态
     */
    @Override
    public void commentMoments(TokenUserInfoDto tokenUserInfoDto, Long momentsId, String content, String replyUserId) {
        UserMoments moments = userMomentsMapper.selectByMomentsId(momentsId);
        if (moments == null || moments.getStatus() != 1) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (StringTools.isEmpty(content)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        UserMomentsComment comment = new UserMomentsComment();
        comment.setMomentsId(momentsId);
        comment.setUserId(tokenUserInfoDto.getUserId());
        comment.setNickName(tokenUserInfoDto.getNickName());
        comment.setContent(StringTools.resetMessageContent(content));
        comment.setCreateTime(System.currentTimeMillis());
        comment.setStatus(1);
        // 如果是回复某人
        if (!StringTools.isEmpty(replyUserId)) {
            UserInfo replyUser = userInfoMapper.selectByUserId(replyUserId);
            if (replyUser != null) {
                comment.setReplyUserId(replyUserId);
                comment.setReplyNickName(replyUser.getNickName());
            }
        }
        userMomentsCommentMapper.insert(comment);
        userMomentsMapper.incrementCommentCount(momentsId, 1);
    }

    /**
     * 获取动态详情(包含评论和点赞列表)
     */
    @Override
    public UserMoments getMomentsDetail(Long momentsId, String currentUserId) {
        UserMoments moments = userMomentsMapper.selectByMomentsId(momentsId);
        if (moments == null || moments.getStatus() != 1) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        fillMomentsDetail(moments, currentUserId);
        return moments;
    }

    /**
     * 填充动态的评论、点赞信息
     */
    private void fillMomentsDetail(UserMoments moments, String currentUserId) {
        // 查询评论列表
        UserMomentsCommentQuery commentQuery = new UserMomentsCommentQuery();
        commentQuery.setMomentsId(moments.getMomentsId());
        commentQuery.setStatus(1);
        commentQuery.setOrderBy("create_time asc");
        List<UserMomentsComment> comments = userMomentsCommentMapper.selectList(commentQuery);
        moments.setComments(comments);

        // 查询点赞列表
        UserMomentsLikeQuery likeQuery = new UserMomentsLikeQuery();
        likeQuery.setMomentsId(moments.getMomentsId());
        likeQuery.setOrderBy("create_time asc");
        List<UserMomentsLike> likes = userMomentsLikeMapper.selectList(likeQuery);
        moments.setLikes(likes);

        // 判断当前用户是否点赞
        if (!StringTools.isEmpty(currentUserId)) {
            boolean liked = false;
            for (UserMomentsLike like : likes) {
                if (currentUserId.equals(like.getUserId())) {
                    liked = true;
                    break;
                }
            }
            moments.setLiked(liked);
        }
    }
}
