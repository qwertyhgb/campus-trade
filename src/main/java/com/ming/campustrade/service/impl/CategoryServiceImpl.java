package com.ming.campustrade.service.impl;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.dto.CategoryAddDTO;
import com.ming.campustrade.dto.CategoryUpdateDTO;
import com.ming.campustrade.entity.Category;
import com.ming.campustrade.mapper.CategoryMapper;
import com.ming.campustrade.service.CategoryService;
import com.ming.campustrade.vo.CategoryVO;

/**
 * 商品分类服务实现类
 *
 * <p><b>继承关系：</b></p>
 * <pre>{@code
 * CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService
 * }</pre>
 * <p>解释每一层：</p>
 * <ul>
 *   <li><b>ServiceImpl&lt;CategoryMapper, Category&gt;</b> —— MyBatis-Plus 提供的通用 Service 实现基类。
 *       泛型参数1是 Mapper 类型，泛型参数2是实体类型。
 *       继承后自动拥有 save()、getById()、updateById()、removeById()、list() 等全套 CRUD 方法，
 *       方法内部直接调用 CategoryMapper，不需要自己写 SQL。</li>
 *   <li><b>CategoryService</b> —— 我们自己定义的 Service 接口，声明了分类模块的业务方法。
 *       实现 implements 后，必须覆写接口中定义的所有方法。</li>
 * </ul>
 *
 * <p><b>分类模块的业务场景：</b><br>
 * 校园二手交易平台的商品分类，例如"数码产品"、"教材书籍"、"生活用品"等。
 * 管理员可以增删改查分类，买家在首页按分类浏览商品。
 * 分类有排序字段（sort），数值越大越靠前；有状态字段（status），1=启用、0=禁用。</p>
 *
 * <p><b>this.xxx() 的来源：</b><br>
 * 代码中使用 this.save()、this.getById()、this.updateById()、this.removeById()、this.list() 等，
 * 这些方法不是这个类自己写的，而是从父类 ServiceImpl 继承来的。
 * this.save(category) 等价于 categoryMapper.insert(category)，
 * this.getById(id) 等价于 categoryMapper.selectById(id)，
 * 只是 ServiceImpl 封装了一层，用起来更方便。</p>
 */
@Slf4j
@Service
@SuppressWarnings("null") // 抑制 MyBatis-Plus Lambda 方法引用与空类型分析冲突的误报警告
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    // ==================== 添加分类 ====================

    /**
     * 添加商品分类（管理员接口）
     *
     * <p><b>流程：</b>校验分类名唯一 → 构建 Category 实体 → 保存到数据库</p>
     *
     * <p><b>为什么要校验分类名唯一？</b><br>
     * 如果允许两个"数码产品"分类同时存在，买家会困惑，商品归类也会混乱。
     * 所以添加前先查一下有没有同名的，有就拒绝。</p>
     *
     * <p><b>sort 字段为什么默认 0？</b><br>
     * sort 是排序权重，数值越大在列表中越靠前。
     * 新添加的分类如果没指定排序值，默认 0（排在最后），管理员后续可以调整。</p>
     *
     * @param categoryAddDTO 添加分类请求参数（名称、图标、排序值）
     * @throws BusinessException 分类名已存在时抛出 CATEGORY_ALREADY_EXISTS
     */
    @Override
    public void addCategory(CategoryAddDTO categoryAddDTO) {
        log.info("添加分类：name={}", categoryAddDTO.getName());

        // 1. 校验分类名是否已存在（唯一性约束）
        //    LambdaQueryWrapper 用 Lambda 引用字段名，比字符串 "name" 更安全——字段改名编译就报错
        //    等价 SQL: SELECT * FROM category WHERE name = ? AND deleted = 0
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getName, categoryAddDTO.getName());
        Category existCategory = this.getOne(wrapper);
        if (existCategory != null) {
            throw new BusinessException(ResultCode.CATEGORY_ALREADY_EXISTS);
        }

        // 2. 构建 Category 实体，把 DTO 中的数据拷贝过来
        //    DTO 是前端传来的数据，Entity 是要存入数据库的数据，两者职责不同
        Category category = new Category();
        category.setName(categoryAddDTO.getName());
        category.setIcon(categoryAddDTO.getIcon());

        // 3. 排序值处理：如果没传 sort，默认为 0（排在最后）
        //    三元运算符：sort != null ? sort : 0
        category.setSort(categoryAddDTO.getSort() != null ? categoryAddDTO.getSort() : 0);

        // 4. 新添加的分类默认启用（1=启用，0=禁用）
        category.setStatus(1);

        // 5. 保存到数据库
        //    this.save() 继承自 ServiceImpl，内部执行 INSERT INTO category (...) VALUES (...)
        //    保存成功后，MyBatis-Plus 会自动把数据库生成的自增 ID 回填到 category.getId()
        this.save(category);
        log.info("添加分类成功：categoryId={}", category.getId());
    }

    // ==================== 修改分类 ====================

    /**
     * 修改商品分类（管理员接口，部分更新）
     *
     * <p><b>流程：</b>查分类是否存在 → 校验新名称唯一 → 部分更新非空字段 → 写回数据库</p>
     *
     * <p><b>什么是"部分更新"（Partial Update）？</b><br>
     * 前端可能只修改了分类名称，图标和排序不变。
     * CategoryUpdateDTO 中除了 name（@NotBlank 必填）外，其他字段都是可选的。
     * 所以这里逐个判断：字段不为 null 才覆盖，为 null 就跳过保持原值。
     * 这样前端只需传需要改的字段，不会把没传的字段覆盖成 null。</p>
     *
     * <p><b>为什么 name 总是更新而其他字段判空？</b><br>
     * name 在 DTO 上标注了 @NotBlank，经过 @Valid 校验后一定不为空，所以直接赋值。
     * icon、sort、status 没有 @NotNull 注解，前端可能不传（为 null），
     * 如果直接 setXxx(dto.getXxx()) 就会把数据库中原来的值覆盖成 null——这是 bug。</p>
     *
     * <p><b>名称唯一性校验的逻辑：</b><br>
     * 只有当新名称和原名称不同时才需要校验。
     * 如果用户只是改了图标没改名称，跳过校验（自己和自己重名不算重复）。</p>
     *
     * @param categoryUpdateDTO 修改分类请求参数（ID 必填，name 必填，其他可选）
     * @throws BusinessException 分类不存在时抛出 CATEGORY_NOT_FOUND，名称重复时抛出 CATEGORY_ALREADY_EXISTS
     */
    @Override
    public void updateCategory(CategoryUpdateDTO categoryUpdateDTO) {
        log.info("修改分类：categoryId={}, name={}", categoryUpdateDTO.getId(), categoryUpdateDTO.getName());

        // 1. 根据 ID 查询分类是否存在
        //    this.getById() 继承自 ServiceImpl，内部执行 SELECT * FROM category WHERE id = ? AND deleted = 0
        Category category = this.getById(categoryUpdateDTO.getId());
        if (category == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }

        // 2. 校验新名称的唯一性（只有名称发生变化时才校验）
        //    用 .equals() 比较而不是 ==，因为 String 是对象，== 比较的是引用地址
        if (!category.getName().equals(categoryUpdateDTO.getName())) {
            // 新名称和原名称不同，需要检查新名称是否已被其他分类使用
            // 等价 SQL: SELECT * FROM category WHERE name = ? AND deleted = 0
            LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Category::getName, categoryUpdateDTO.getName());
            Category existCategory = this.getOne(wrapper);
            if (existCategory != null) {
                throw new BusinessException(ResultCode.CATEGORY_ALREADY_EXISTS);
            }
        }

        // 3. 部分更新：只覆盖 DTO 中非空的字段，null 字段保持数据库原值
        //    name 是 @NotBlank 必填字段，一定不为空，直接赋值
        category.setName(categoryUpdateDTO.getName());

        //    icon：只有前端传了才更新（StringUtils.hasText 排除 null、空串、纯空白）
        if (StringUtils.hasText(categoryUpdateDTO.getIcon())) {
            category.setIcon(categoryUpdateDTO.getIcon());
        }

        //    sort：只有前端传了才更新
        if (categoryUpdateDTO.getSort() != null) {
            category.setSort(categoryUpdateDTO.getSort());
        }

        //    status：只有前端传了才更新（防止把启用状态意外覆盖成 null）
        if (categoryUpdateDTO.getStatus() != null) {
            category.setStatus(categoryUpdateDTO.getStatus());
        }

        // 4. 写回数据库
        //    this.updateById() 内部执行 UPDATE category SET ... WHERE id = ? AND deleted = 0
        //    MyBatis-Plus 只会更新实体中被 set 过的非 null 字段，未改动的字段不会出现在 SQL 中
        this.updateById(category);
        log.info("修改分类成功：categoryId={}", category.getId());
    }

    // ==================== 删除分类 ====================

    /**
     * 删除商品分类（管理员接口，逻辑删除）
     *
     * <p><b>逻辑删除 vs 物理删除：</b></p>
     * <ul>
     *   <li><b>物理删除</b>（DELETE FROM category WHERE id=?）：数据真的从数据库消失，不可恢复</li>
     *   <li><b>逻辑删除</b>（UPDATE category SET deleted=1 WHERE id=?）：只是标记 deleted=1，数据还在</li>
     * </ul>
     * <p>使用逻辑删除的原因：分类下可能还有商品引用，直接物理删除会导致数据不一致；
     * 而且管理员可能误删，逻辑删除保留了恢复的可能性。</p>
     *
     * <p><b>this.removeById() 为什么是逻辑删除？</b><br>
     * 因为 Category 实体的 deleted 字段标注了 @TableLogic，
     * MyBatis-Plus 会自动把 removeById 的 DELETE 改写成 UPDATE SET deleted=1。
     * 后续所有查询也会自动追加 WHERE deleted=0，过滤掉已删除的记录。</p>
     *
     * @param id 分类 ID
     * @throws BusinessException 分类不存在时抛出 CATEGORY_NOT_FOUND
     */
    @Override
    public void deleteCategory(Long id) {
        log.info("删除分类：categoryId={}", id);

        // 1. 查分类是否存在
        Category category = this.getById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }

        // 2. 逻辑删除（@TableLogic 自动把 DELETE 转成 UPDATE SET deleted=1）
        this.removeById(category);
        log.info("删除分类成功：categoryId={}", id);
    }

    // ==================== 查询分类列表 ====================

    /**
     * 查询所有启用的分类列表（按排序值倒序）
     *
     * <p><b>流程：</b>构建查询条件（status=1 + 按 sort 倒序）→ 查询 → 转换为 VO 列表</p>
     *
     * <p><b>为什么只查 status=1 的？</b><br>
     * status=0 表示分类被禁用（管理员可能临时下线某个分类），
     * 禁用的分类不应该展示给买家，所以查询时过滤掉。</p>
     *
     * <p><b>为什么按 sort 倒序（orderByDesc）？</b><br>
     * sort 是排序权重，数值越大越重要/越常用，应该排在前面。
     * 例如"数码产品"sort=100 排在"其他"sort=0 前面。
     * 倒序排列让权重高的分类优先展示。</p>
     *
     * <p><b>stream().map().toList() 是什么？</b><br>
     * Java Stream API 的链式操作：
     * 1. stream() —— 把 List 转成流；
     * 2. map() —— 对流中每个元素做转换（Category → CategoryVO）；
     * 3. toList() —— 收集结果为不可变 List（Java 16+ 的写法，比 collect(Collectors.toList()) 更简洁）。</p>
     *
     * @return 启用状态的分类 VO 列表（按 sort 倒序）
     */
    @Override
    public List<CategoryVO> getCategoryList() {
        log.info("查询分类列表");

        // 1. 构建查询条件：只查启用的分类，按排序值倒序
        //    等价 SQL: SELECT * FROM category WHERE status = 1 AND deleted = 0 ORDER BY sort DESC
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getStatus, 1).orderByDesc(Category::getSort);

        // 2. 执行查询
        List<Category> categories = this.list(wrapper);

        // 3. 实体列表转 VO 列表（过滤掉敏感/内部字段，只保留前端需要的）
        return categories.stream().map(CategoryServiceImpl::convertToCategoryVO).toList();
    }

    // ==================== 查询分类详情 ====================

    /**
     * 根据 ID 查询分类详情
     *
     * <p><b>流程：</b>根据 ID 查分类 → 判空 → 转换为 VO 返回</p>
     *
     * <p><b>使用场景：</b><br>
     * 管理员编辑分类时，前端需要先查出分类的当前值填充到表单中；
     * 或者商品详情页需要显示所属分类的名称和图标。</p>
     *
     * @param id 分类 ID
     * @return 分类视图对象
     * @throws BusinessException 分类不存在时抛出 CATEGORY_NOT_FOUND
     */
    @Override
    public CategoryVO getCategory(Long id) {
        log.info("查询分类详情：categoryId={}", id);

        // 1. 根据 ID 查询分类（MyBatis-Plus 自动加 WHERE deleted=0，已删除的查不到）
        Category category = this.getById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }

        // 2. 实体转 VO 返回
        return convertToCategoryVO(category);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 实体转 VO（Category → CategoryVO）
     *
     * <p><b>为什么需要转换？</b><br>
     * Category 实体对应数据库表结构，可能包含 deleted 等内部字段。
     * CategoryVO 是给前端看的视图对象，只包含前端需要展示的字段。
     * 这种"实体 → VO"的转换在分层架构中很常见，目的是隔离数据层和展示层。</p>
     *
     * <p><b>为什么是 static？</b><br>
     * 这个方法不依赖实例状态（不用 this.xxx），只是纯粹的属性拷贝。
     * 标记 static 后可以通过类名直接调用（CategoryServiceImpl::convertToCategoryVO），
     * 也可以作为方法引用传给 stream().map()。</p>
     *
     * @param category 分类实体（从数据库查出的）
     * @return 分类视图对象
     */
    private static CategoryVO convertToCategoryVO(Category category) {
        CategoryVO vo = new CategoryVO();
        vo.setId(category.getId());
        vo.setName(category.getName());
        vo.setIcon(category.getIcon());
        vo.setSort(category.getSort());
        vo.setStatus(category.getStatus());
        vo.setCreateTime(category.getCreateTime());
        return vo;
    }
}
