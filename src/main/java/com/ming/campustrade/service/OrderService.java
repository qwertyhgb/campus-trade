package com.ming.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ming.campustrade.dto.OrderPlaceDTO;
import com.ming.campustrade.entity.Order;
import com.ming.campustrade.vo.OrderVO;

public interface OrderService extends IService<Order> {
    
    void placeOrder(OrderPlaceDTO orderPlaceDTO);

    void confirmOrder(Long id);

    void cancelOrder(Long id);

    OrderVO getOrderById(Long id);

    IPage<OrderVO> getBuyOrder(Integer pageNo, Integer pageSize);

    IPage<OrderVO> getSellOrder(Integer pageNo, Integer pageSize);
}
