package com.ming.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ming.campustrade.dto.OrderPlaceDTO;
import com.ming.campustrade.entity.Order;
import com.ming.campustrade.vo.OrderVO;

/**
 * 订单业务逻辑接口（Service 层）。
 *
 * <p>负责处理下单、确认、取消以及订单查询等核心交易逻辑。
 * 继承 MyBatis-Plus 的 {@link IService} 后自动获得针对 {@link Order} 的通用 CRUD 能力
 * （{@code save}、{@code removeById}、{@code getById}、{@code page} 等），
 * 因此这里只声明本项目特有的业务方法。</p>
 *
 * @author ming
 */
public interface OrderService extends IService<Order> {

    /**
     * 买家下单。
     *
     * <p>实现时需校验商品是否存在/在售，生成订单与商品快照，并将商品置为锁定状态以避免超卖。</p>
     *
     * @param orderPlaceDTO 下单参数（包含商品 ID）
     */
    void placeOrder(OrderPlaceDTO orderPlaceDTO);

    /**
     * 确认订单，将订单状态置为"已确认"，并完成交易。
     *
     * @param id 订单主键 ID
     */
    void confirmOrder(Long id);

    /**
     * 取消订单，将订单状态置为"已取消"，并恢复商品为可售状态。
     *
     * @param id 订单主键 ID
     */
    void cancelOrder(Long id);

    /**
     * 根据 ID 查询订单详情。
     *
     * @param id 订单主键 ID
     * @return 订单视图对象（含买家/卖家昵称等关联信息）
     */
    OrderVO getOrderById(Long id);

    /**
     * 分页查询"我买到的"订单（当前登录用户作为买家）。
     *
     * @param pageNo   页码
     * @param pageSize 每页条数
     * @return 分页结果，{@link IPage} 内含数据列表与总条数等分页信息
     */
    IPage<OrderVO> getBuyOrder(Integer pageNo, Integer pageSize);

    /**
     * 分页查询"我卖出的"订单（当前登录用户作为卖家）。
     *
     * @param pageNo   页码
     * @param pageSize 每页条数
     * @return 分页结果，{@link IPage} 内含数据列表与总条数等分页信息
     */
    IPage<OrderVO> getSellOrder(Integer pageNo, Integer pageSize);

    /**
     * 管理员分页查询平台全部订单（可按状态筛选）。
     *
     * @param status   订单状态筛选（null 表示查全部状态）
     * @param pageNo   页码
     * @param pageSize 每页条数
     * @return 分页结果，含买卖双方昵称等关联信息
     */
    IPage<OrderVO> listOrdersForAdmin(Integer status, Integer pageNo, Integer pageSize);
}
