package com.ming.campustrade.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ming.campustrade.dto.ActivityCategoryDTO;
import com.ming.campustrade.entity.ActivityCategory;
import com.ming.campustrade.vo.ActivityCategoryVO;

/**
 * 活动分类业务逻辑接口（Service 层）。
 *
 * <p>负责活动分类的增删查业务编排。继承 MyBatis-Plus 的 {@link IService} 后，
 * 自动获得针对 {@link ActivityCategory} 的通用 CRUD 能力，这里只声明本项目特有的业务方法。</p>
 *
 * @author ming
 */
public interface ActivityCategoryService extends IService<ActivityCategory> {

    /**
     * 新增活动分类。
     *
     * @param dto 新增分类参数（名称、排序值）
     */
    void addCategory(ActivityCategoryDTO dto);

    /**
     * 修改活动分类。
     *
     * @param dto 修改分类参数（必须携带分类 ID）
     */
    void updateCategory(ActivityCategoryDTO dto);

    /**
     * 删除活动分类（物理删除）。
     *
     * <p>若该分类下还有活动，则拒绝删除。</p>
     *
     * @param id 分类主键 ID
     */
    void deleteCategory(Long id);

    /**
     * 查询全部活动分类列表（按 sort 升序）。
     *
     * @return 活动分类视图对象列表
     */
    List<ActivityCategoryVO> getCategoryList();
}
