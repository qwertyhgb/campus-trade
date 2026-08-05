package com.ming.campustrade.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ming.campustrade.entity.Activity;

/**
 * 活动数据访问层（Mapper / DAO），操作 {@code activity} 表。
 *
 * <p>继承 {@link BaseMapper} 即可获得单表常用 CRUD 能力，无需手写 SQL。
 * 复杂的多表查询（如活动列表 + 分类名称）在 Service 层组装或后续补充。</p>
 *
 * @author ming
 */
@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {

    /**
     * 悲观锁查询：按主键查活动并加行锁（SELECT ... FOR UPDATE）。
     *
     * <p><b>和普通 selectById 的区别：</b><br>
     * FOR UPDATE 会给查到的这一行加<b>排他锁</b>（写锁）：
     * 锁持有期间，其他事务对同一行的 UPDATE / DELETE / SELECT FOR UPDATE 都会阻塞等待，
     * 直到本事务提交或回滚后锁才释放。普通 SELECT（不加 FOR UPDATE）不受影响，仍可读。</p>
     *
     * <p><b>使用场景：</b>候补模块的"读队列最大值 → 计算新位置 → 插入"三步操作。
     * 如果不加锁，两个用户同时读到 MAX=5，都会插入 position=6，队列就乱了。
     * 加锁后同一活动的候补加入变成串行：A 插入 6 并提交，B 才能读到 MAX=6 插入 7。</p>
     *
     * <p><b>必须配合 @Transactional 使用（关键）：</b><br>
     * FOR UPDATE 的锁在事务提交/回滚时才释放。如果方法没有事务，
     * 查询结束后锁立即释放，等于没锁。调用方必须在事务内调用本方法。</p>
     *
     * @param id 活动主键 ID
     * @return 活动实体；不存在返回 null
     */
    @Select("SELECT * FROM activity WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Activity selectByIdForUpdate(@Param("id") Long id);
}
