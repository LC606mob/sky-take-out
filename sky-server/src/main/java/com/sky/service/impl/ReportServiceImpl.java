package com.sky.service.impl;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportServiceImpl implements ReportService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    /**
     * 统计指定时间区间内的营业额
     * @param begin
     * @param end
     * @return
     */
    public TurnoverReportVO getTurnover(LocalDate begin, LocalDate end) {
        //业务逻辑：
        //需要完成日期计算、循环查询和数据拼接：
        //1. 计算日期列表：从 begin 开始到 end 结束，逐日存入 List<LocalDate>。
        //2. 循环统计金额：遍历日期集合，通过 LocalDateTime 获取每一天的起始时间（00:00:00）和结束时间（23:59:59）。
        //3. 调用持久层：利用 orderMapper.sumByMap 统计当天状态为“已完成”的金额总和。
        //4. 空值处理：如果当天没有金额，数据库返回为 null，需手动转为 0.0。
        //5. 格式转换：使用 StringUtils.join 将数据集合转换为逗号分隔的字符串

        //dateList
        List<LocalDate> dateList = new ArrayList<>();
        dateList.add(begin);
            while (!begin.equals(end)){
                //日期计算，结算指定日期后1天的日期
                begin = begin.plusDays(1);
                dateList.add(begin);
            }

        //turnoverList
        //当前集合用于存放从begin到end范围内的每天的营业额
        List<Double> turnoverList = new ArrayList<>();
        for (LocalDate date : dateList) {
            //查询date日期对应的营业额数据，营业额指：状态为“已完成”的订单金额合计
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);
            Map map = new HashMap<>();
            map.put("status",Orders.COMPLETED);
            map.put("begin",beginTime);
            map.put("end",endTime);
            //select sum(amount) from orders where status = 5 and order_time between #{} and #{};
            Double turnover = orderMapper.sumByMap(map);
            turnover = turnover == null ? 0.0 : turnover;
            turnoverList.add(turnover);
        }


        return TurnoverReportVO.builder()
                .dateList(StringUtils.join(dateList,","))
                .turnoverList(StringUtils.join(turnoverList,","))
                .build();
    }

    /**
     * 用户数据统计
     * @param begin
     * @param end
     * @return
     */
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        //业务逻辑：
        //核心逻辑在于循环计算每一天的新增用户和总人数：
        //1. 计算日期轴：从 begin 到 end 构造日期列表 dateList。
        //2. 循环统计数量：
        //    ◦ 遍历日期列表，计算每日的起始时间（00:00:00）和结束时间（23:59:59）。
        //    ◦ 查询新增用户：调用 userMapper.countByMap，传入当天的起始和结束时间。
        //    ◦ 查询总用户量：调用 userMapper.countByMap，仅传入当天的结束时间作为上限。
        //3. 数据拼接：使用 StringUtils.join 将集合转换为逗号分隔的字符串

        List<LocalDate> dateList = new ArrayList<>();
        dateList.add(begin);
        while (!begin.equals(end)){
            begin = begin.plusDays(1);
            dateList.add(begin);
        }
        //存放每天发总用户数select count(id) from user where create_time < ?
        List<Integer> totalUserList = new ArrayList<>();
        //存放每天的新增用户数select count(id) from user where create_time < ? and creat_time > ?
        List<Integer> newUserList = new ArrayList<>();

        for (LocalDate date : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(date,LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date,LocalTime.MAX);
            Map map = new HashMap<>();
            map.put("end",endTime);//总用户数量 select count(id) from user where  create_time < ?
            Integer totalUser = userMapper.countByMap(map);
            map.put("begin",beginTime);
            //新增用户数量 select count(id) from user where create_time > ? and create_time < ?
            Integer newUser = userMapper.countByMap(map);
            totalUserList.add(totalUser);
            newUserList.add(newUser);
        }

        return UserReportVO.builder()
                .dateList(StringUtils.join(dateList,","))
                .newUserList(StringUtils.join(newUserList,","))
                .totalUserList(StringUtils.join(totalUserList,","))
                .build();
    }
}
