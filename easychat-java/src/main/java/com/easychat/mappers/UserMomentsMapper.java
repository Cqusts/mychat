package com.easychat.mappers;

import org.apache.ibatis.annotations.Param;

/**
 * 朋友圈动态表 数据库操作接口
 */
public interface UserMomentsMapper<T,P> extends BaseMapper<T,P> {

	/**
	 * 根据MomentsId更新
	 */
	 Integer updateByMomentsId(@Param("bean") T t,@Param("momentsId") Long momentsId);


	/**
	 * 根据MomentsId删除
	 */
	 Integer deleteByMomentsId(@Param("momentsId") Long momentsId);


	/**
	 * 根据MomentsId获取对象
	 */
	 T selectByMomentsId(@Param("momentsId") Long momentsId);


	/**
	 * 增加点赞数
	 */
	 Integer incrementLikeCount(@Param("momentsId") Long momentsId, @Param("count") Integer count);


	/**
	 * 增加评论数
	 */
	 Integer incrementCommentCount(@Param("momentsId") Long momentsId, @Param("count") Integer count);

}
