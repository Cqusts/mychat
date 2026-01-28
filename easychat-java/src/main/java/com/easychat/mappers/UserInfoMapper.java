package com.easychat.mappers;

import org.apache.ibatis.annotations.Param;

/**
 *  数据库操作接口
 * 这是一个泛型接口，继承自BaseMapper，提供了针对用户信息的数据库操作方法
 * @param <T> 泛型类型，表示实体类
 * @param <P> 泛型类型，表示主键类型
 */
public interface UserInfoMapper<T,P> extends BaseMapper<T,P> {

	/**
	 * 根据UserId更新
 * 该方法用于根据用户ID更新指定对象的信息
 *
 * @param t 需要更新的对象，使用@Param注解标记为"bean"
 * @param userId 用户ID，用于标识需要更新哪个用户的数据，使用@Param注解标记为"userId"
 * @return 返回更新的记录数，通常为1表示更新成功，0表示未找到匹配记录
	 */
	 Integer updateByUserId(@Param("bean") T t,@Param("userId") String userId);


	/**
	 * 根据UserId删除
	 */
	 Integer deleteByUserId(@Param("userId") String userId);


	/**
	 * 根据UserId获取对象
	 */
	 T selectByUserId(@Param("userId") String userId);


	/**
	 * 根据Email更新
	 */
	 Integer updateByEmail(@Param("bean") T t,@Param("email") String email);


	/**
	 * 根据Email删除
	 */
	 Integer deleteByEmail(@Param("email") String email);


	/**
	 * 根据Email获取对象
	 */
	 T selectByEmail(@Param("email") String email);


}
