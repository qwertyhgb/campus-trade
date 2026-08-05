package com.ming.campustrade.service.impl;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.dto.ActivityCategoryDTO;
import com.ming.campustrade.entity.Activity;
import com.ming.campustrade.entity.ActivityCategory;
import com.ming.campustrade.mapper.ActivityCategoryMapper;
import com.ming.campustrade.mapper.ActivityMapper;
import com.ming.campustrade.service.ActivityCategoryService;
import com.ming.campustrade.vo.ActivityCategoryVO;

/**
 * 活动分类服务实现类。
 *
 * <p>继承 {@link ServiceImpl} 获得 {@link ActivityCategory} 的通用 CRUD 能力，
 * 额外注入 {@link ActivityMapper} 用于删除分类前检查是否有活动引用。</p>
 *
 * @author ming
 */
@Slf4j
@Service
@SuppressWarnings("null") // 抑制 MyBatis-Plus Lambda 方法引用与空类型分析冲突的误报警告
public class ActivityCategoryServiceImpl
        extends ServiceImpl<ActivityCategoryMapper, ActivityCategory>
        implements ActivityCategoryService {

    /** 活动 Mapper，删除分类前检查该分类下是否还有活动。 */
    private final ActivityMapper activityMapper;

    public ActivityCategoryServiceImpl(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    // ==================== 添加分类 ====================

    /**
     * 添加活动分类（管理员接口）。
     *
     * <p>流程：校验分类名唯一 → 构建实体 → 保存到数据库。</p>
     *
     * @param dto 添加分类参数（名称、排序值）
     * @throws BusinessException 分类名已存在时抛出 ACTIVITY_CATEGORY_ALREADY_EXISTS
     */
    @Override
    public void addCategory(ActivityCategoryDTO dto) {
        log.info("添加活动分类：name={}", dto.getName());

        // 1. 校验分类名是否已存在（唯一性约束）
        //    等价 SQL: SELECT * FROM activity_category WHERE name = ?
        LambdaQueryWrapper<ActivityCategory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ActivityCategory::getName, dto.getName());
        if (this.getOne(wrapper) != null) {
            throw new BusinessException(ResultCode.ACTIVITY_CATEGORY_ALREADY_EXISTS);
        }

        // 2. 构建实体，sort 未传时默认 0
        ActivityCategory category = new ActivityCategory();
        category.setName(dto.getName());
        category.setSort(dto.getSort() != null ? dto.getSort() : 0);

        // 3. 保存到数据库（自增 ID 自动回填）
        this.save(category);
        log.info("添加活动分类成功：categoryId={}", category.getId());
    }

    // ==================== 修改分类 ====================

    /**
     * 修改活动分类（管理员接口）。
     *
     * <p>流程：查分类是否存在 → 名称变化时校验唯一 → 更新。</p>
     *
     * @param dto 修改分类参数（ID 必填）
     * @throws BusinessException 分类不存在时抛出 ACTIVITY_CATEGORY_NOT_FOUND，名称重复时抛出 ACTIVITY_CATEGORY_ALREADY_EXISTS
     */
    @Override
    public void updateCategory(ActivityCategoryDTO dto) {
        log.info("修改活动分类：categoryId={}, name={}", dto.getId(), dto.getName());

        // 1. 修改场景下 ID 必须存在；DTO 复用于新增，因此不能只依赖 DTO 注解校验
        if (dto.getId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "修改活动分类时必须提供分类ID");
        }

        // 2. 查分类是否存在
        ActivityCategory category = this.getById(dto.getId());
        if (category == null) {
            throw new BusinessException(ResultCode.ACTIVITY_CATEGORY_NOT_FOUND);
        }

        // 3. 名称发生变化时校验唯一性（自己和自己重名不算重复）
        if (!category.getName().equals(dto.getName())) {
            LambdaQueryWrapper<ActivityCategory> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ActivityCategory::getName, dto.getName());
            if (this.getOne(wrapper) != null) {
                throw new BusinessException(ResultCode.ACTIVITY_CATEGORY_ALREADY_EXISTS);
            }
            category.setName(dto.getName());
        }

        // 4. sort 传了才更新（部分更新，未传保持原值）
        if (dto.getSort() != null) {
            category.setSort(dto.getSort());
        }

        // 5. 写回数据库
        this.updateById(category);
        log.info("修改活动分类成功：categoryId={}", category.getId());
    }

    // ==================== 删除分类 ====================

    /**
     * 删除活动分类（管理员接口，物理删除）。
     *
     * <p>删除前检查该分类下是否还有活动，有则拒绝删除，防止活动失去分类归属。</p>
     *
     * @param id 分类 ID
     * @throws BusinessException 分类不存在时抛出 ACTIVITY_CATEGORY_NOT_FOUND，分类下有活动时抛出 ACTIVITY_CATEGORY_IN_USE
     */
    @Override
    public void deleteCategory(Long id) {
        log.info("删除活动分类：categoryId={}", id);

        // 1. 删除场景下 ID 不能为空，避免空主键进入持久层查询
        if (id == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "删除活动分类时必须提供分类ID");
        }

        // 2. 查分类是否存在
        ActivityCategory category = this.getById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.ACTIVITY_CATEGORY_NOT_FOUND);
        }

        // 3. 检查该分类下是否还有活动（有则拒绝删除）
        //    等价 SQL: SELECT COUNT(*) FROM activity WHERE category_id = ? AND deleted = 0
        Long activityCount = activityMapper.selectCount(
                new LambdaQueryWrapper<Activity>()
                        .eq(Activity::getCategoryId, id)
                        .eq(Activity::getDeleted, 0));
        if (activityCount != null && activityCount > 0) {
            throw new BusinessException(ResultCode.ACTIVITY_CATEGORY_IN_USE);
        }

        // 4. 物理删除（activity_category 表无 deleted 字段）
        //    注：activity_category 表设计为物理删除（无 @TableLogic），removeById 执行真正的 DELETE
        this.removeById(category);
        log.info("删除活动分类成功：categoryId={}", id);
    }

    // ==================== 查询分类列表 ====================

    /**
     * 查询所有活动分类列表（按 sort 升序，越小越靠前）。
     *
     * @return 活动分类 VO 列表
     */
    @Override
    public List<ActivityCategoryVO> getCategoryList() {
        log.info("查询活动分类列表");

        // 按 sort 升序排列（sort 越小越靠前，与表设计注释一致）
        // 等价 SQL: SELECT * FROM activity_category ORDER BY sort ASC
        LambdaQueryWrapper<ActivityCategory> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(ActivityCategory::getSort);
        List<ActivityCategory> categories = this.list(wrapper);

        return categories.stream()
                .map(ActivityCategoryServiceImpl::convertToVO)
                .toList();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 实体转 VO（ActivityCategory → ActivityCategoryVO）。
     *
     * @param category 分类实体
     * @return 分类视图对象
     */
    private static ActivityCategoryVO convertToVO(ActivityCategory category) {
        ActivityCategoryVO vo = new ActivityCategoryVO();
        vo.setId(category.getId());
        vo.setName(category.getName());
        vo.setSort(category.getSort());
        vo.setCreateTime(category.getCreateTime());
        return vo;
    }
}
