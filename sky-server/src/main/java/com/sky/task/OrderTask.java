package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

/**
 * 自定义定时任务，实现订单状态定时处理
 */
@Component
@Slf4j
public class OrderTask {

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 处理待支付订单，每分钟处理一次
     */
    @Scheduled(cron = "0 * * * * ?")
    public void processTimeoutOrder(){
        //业务逻辑：
        //A. 处理支付超时订单
        //• 触发频率：每分钟触发一次（cron = "0 * * * * ?"）。
        //• 判定标准：下单时间小于（当前时间 - 15分钟），且状态为“待付款”。
        //• 操作：将符合条件的订单状态改为“已取消”，并记录取消原因
        log.info("处理支付超时订单{}",new Date()); //new Date():获取程序运行到这一行代码时的精确时间（包含年月日时分秒）

        //select * from orders where status = 1 and order_time < (当前时间
        LocalDateTime time = LocalDateTime.now().plusMinutes(-15);
        List<Orders> ordersList = orderMapper.getByStatusAndOrdertimeLT(Orders.PENDING_PAYMENT,time);
        for (Orders order : ordersList) {
            order.setStatus(Orders.CANCELLED);
            order.setCancelReason("订单超时，自动取消");
            order.setCancelTime(LocalDateTime.now());
            orderMapper.update(order);
        }
    }

    /**
     * 处理“派送中”状态的订单
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void processDeliveryOrder(){
        //业务逻辑；
        //B. 处理一直处于“派送中”的订单
        //• 触发频率：每天凌晨 1 点触发一次（cron = "0 0 1 * * ?"）。
        //• 判定标准：状态为“派送中”的订单。
        //• 操作：将状态自动修改为“已完成”，以确保存档数据的完整性
        log.info("处理派送中超时订单{}",new Date()); //new Date():获取程序运行到这一行代码时的精确时间（包含年月日时分秒）

        //select * from orders where status = 1 and order_time < (当前时间
        LocalDateTime time = LocalDateTime.now().plusMinutes(-60);
        List<Orders> ordersList = orderMapper.getByStatusAndOrdertimeLT(Orders.DELIVERY_IN_PROGRESS,time);
        for (Orders order : ordersList) {
            order.setStatus(Orders.COMPLETED);
            orderMapper.update(order);
        }
    }

}