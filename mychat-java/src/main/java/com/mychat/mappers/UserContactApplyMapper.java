package com.mychat.mappers;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 联系人申请 数据库操作接口
 */
public interface UserContactApplyMapper<T,P> extends BaseMapper<T,P> {

	/**
	 * 游标分页查询申请列表（按apply_id倒序）
	 */
	 List<T> selectApplyListByPage(@Param("query") P p);

	/**
	 * 根据ApplyId更新
	 */
	 Integer updateByApplyId(@Param("bean") T t,@Param("applyId") Integer applyId);


	/**
	 * 根据ApplyId删除
	 */
	 Integer deleteByApplyId(@Param("applyId") Integer applyId);


	/**
	 * 根据ApplyId获取对象
	 */
	 T selectByApplyId(@Param("applyId") Integer applyId);


	/**
	 * 根据ApplyUserIdAndReceiveUserIdAndContactId更新
	 */
	 Integer updateByApplyUserIdAndReceiveUserIdAndContactId(@Param("bean") T t,@Param("applyUserId") String applyUserId,@Param("receiveUserId") String receiveUserId,@Param("contactId") String contactId);


	/**
	 * 根据ApplyUserIdAndReceiveUserIdAndContactId删除
	 */
	 Integer deleteByApplyUserIdAndReceiveUserIdAndContactId(@Param("applyUserId") String applyUserId,@Param("receiveUserId") String receiveUserId,@Param("contactId") String contactId);


	/**
	 * 根据ApplyUserIdAndReceiveUserIdAndContactId获取对象
	 */
	 T selectByApplyUserIdAndReceiveUserIdAndContactId(@Param("applyUserId") String applyUserId,@Param("receiveUserId") String receiveUserId,@Param("contactId") String contactId);


}
