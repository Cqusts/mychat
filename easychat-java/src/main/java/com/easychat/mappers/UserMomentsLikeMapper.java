package com.easychat.mappers;

import org.apache.ibatis.annotations.Param;

/**
 * 朋友圈点赞表 数据库操作接口
 */
public interface UserMomentsLikeMapper<T,P> extends BaseMapper<T,P> {

	/**
	 * 根据MomentsId和UserId更新
	 */
	 Integer updateByMomentsIdAndUserId(@Param("bean") T t,@Param("momentsId") Long momentsId,@Param("userId") String userId);


	/**
	 * 根据MomentsId和UserId删除
	 */
	 Integer deleteByMomentsIdAndUserId(@Param("momentsId") Long momentsId,@Param("userId") String userId);


	/**
	 * 根据MomentsId和UserId获取对象
	 */
	 T selectByMomentsIdAndUserId(@Param("momentsId") Long momentsId,@Param("userId") String userId);

}
