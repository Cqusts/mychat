package com.easychat.mappers;

import org.apache.ibatis.annotations.Param;

/**
 * 朋友圈评论表 数据库操作接口
 */
public interface UserMomentsCommentMapper<T,P> extends BaseMapper<T,P> {

	/**
	 * 根据CommentId更新
	 */
	 Integer updateByCommentId(@Param("bean") T t,@Param("commentId") Long commentId);


	/**
	 * 根据CommentId删除
	 */
	 Integer deleteByCommentId(@Param("commentId") Long commentId);


	/**
	 * 根据CommentId获取对象
	 */
	 T selectByCommentId(@Param("commentId") Long commentId);

}
