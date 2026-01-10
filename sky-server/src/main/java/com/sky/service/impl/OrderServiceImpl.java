package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.xiaoymin.knife4j.core.util.CollectionUtils;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WebSocketServer webSocketServer;
    @Value("${sky.shop.address}")
    private String shopAddress;
    @Value("${sky.baidu.ak}")
    private String ak;



    @Autowired
    private WeChatPayUtil weChatPayUtil;

    /**
     * 用户下单
     *
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        //异常情况的处理（收货地址为空、购物车为空）

        //收货地址为空
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null){
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        //购物车为空
        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);

        //检查用户的收货地址是否超出配送范围
        checkOutOfRange(addressBook.getCityName() + addressBook.getDistrictName() + addressBook.getDetail());

        //查询当前用户的购物车数据
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);

        if (shoppingCartList == null || shoppingCartList.size() == 0){
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        //构造订单数据
        Orders order = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO,order);
        order.setPhone(addressBook.getPhone());
        order.setAddress(addressBook.getDetail());
        order.setConsignee(addressBook.getConsignee());
        order.setNumber(String.valueOf(System.currentTimeMillis()));
        order.setUserId(userId);
        order.setStatus(Orders.PENDING_PAYMENT);
        order.setPayStatus(Orders.UN_PAID);
        order.setOrderTime(LocalDateTime.now());

        //向订单表插入1条数据
        orderMapper.insert(order);

        //订单明细数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart,orderDetail);
            orderDetail.setOrderId(order.getId());
            orderDetailList.add(orderDetail);
        }
        //向明细表插入n条数据
        orderDetailMapper.insertBatch(orderDetailList);

        //清理购物车中的数据
        shoppingCartMapper.deleteByUserId(userId);

        //封装返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(order.getId())
                .orderAmount(order.getAmount())
                .orderTime(order.getOrderTime())
                .orderNumber(order.getNumber())
                .build();

        return orderSubmitVO;
    }

//    /**
//     * 订单支付
//     *
//     * @param ordersPaymentDTO
//     * @return
//     */
//    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
//        // 当前登录用户id
//        Long userId = BaseContext.getCurrentId();
//        User user = userMapper.getByUserId(userId);
//
//        // 【修改点2】 直接跳过微信支付调用，创建一个空的 JSON 对象
//        // JSONObject jsonObject = weChatPayUtil.pay(
//        //         ordersPaymentDTO.getOrderNumber(),
//        //         new BigDecimal(0.01),
//        //         "苍穹外卖订单",
//        //         user.getOpenid()
//        // );
//
//        // 模拟一个空的返回结果，防止报错
//        JSONObject jsonObject = new JSONObject();
//
//        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
//            throw new OrderBusinessException("该订单已支付");
//        }
//
//        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
//        vo.setPackageStr(jsonObject.getString("package"));
//
//        return vo;
//    }

    /**
     * 订单支付（跳过微信支付，直接修改为成功）
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 1. 获取当前登录用户id (保持原逻辑)
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getByUserId(userId);

        // 2. 【核心修改】直接调用支付成功的方法
        // 我们利用订单号，直接告诉系统：这个订单已经支付成功了！
        // paySuccess方法通常就在当前类下面，它负责修改数据库状态为“已支付”
        // 2. 【核心修改】直接调用支付成功的方法
        // 因为没有微信回调，我们在这里手动触发“支付成功”的后续逻辑（改状态 + 推送WebSocket）
        paySuccess(ordersPaymentDTO.getOrderNumber());

        // 3. 返回一个空对象
        // 因为前端原本期待返回微信支付的参数，现在不需要了，返回个空的或者随便塞点东西防止报错即可
        OrderPaymentVO vo = new OrderPaymentVO();
        vo.setNonceStr("666"); // 随便填，反正不用
        vo.setPaySign("666");  // 随便填
        vo.setTimeStamp("666");// 随便填
        vo.setSignType("666"); // 随便填
        vo.setPackageStr("666");

        return vo;
    }


    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {

        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);

        //////////////////////////////////////////////
        Map map = new HashMap();
        map.put("type", 1);//消息类型，1表示来单提醒
        map.put("orderId", orders.getId());
        map.put("content", "订单号：" + outTradeNo);

        //通过WebSocket实现来单提醒，向客户端浏览器推送消息
        webSocketServer.sendToAllClient(JSON.toJSONString(map));
        ///////////////////////////////////////////////////
    }

    /**
     * 用户端订单分页查询
     *
     * @param pageNum
     * @param pageSize
     * @param status
     * @return
     */
    public PageResult pageQuery4User(int pageNum, int pageSize, Integer status) {
        // 设置分页
        PageHelper.startPage(pageNum, pageSize);

        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        // 分页条件查询
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> list = new ArrayList();

        // 查询出订单明细，并封装入OrderVO进行响应
        if (page != null && page.getTotal() > 0) {
            for (Orders orders : page) {
                Long orderId = orders.getId();// 订单id

                // 查询订单明细
                List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orderId);

                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetails);

                list.add(orderVO);
            }
        }
        return new PageResult(page.getTotal(), list);
    }

    @Transactional
    public OrderVO details(Long id) {
        //业务逻辑：
        //1. 查询订单主表：调用 orderMapper.getById(id) 获取订单基本信息。
        Orders order = orderMapper.getById(id);
        //2. 查询订单明细表：调用 orderDetailMapper.getByOrderId(id) 获取该订单关联的所有菜品/套餐明细。
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);
        //3. 封装 VO 对象：创建 OrderVO 对象，利用 BeanUtils.copyProperties 拷贝基本属性，并将明细列表设置进去。
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(order,orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    @Override
    public void userCancelById(Long id) {
        //订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消 7已退款
        //支付状态 0未支付 1已支付 2退款
        //业务逻辑：
        //直接取消：只有处于“待支付”或“待接单”状态时，用户才可以直接在小程序端取消订单。
        //电话沟通：若订单已处于“已接单”或“派送中”状态，用户需通过电话与商家沟通取消。
        //退款逻辑：如果订单在“待接单”状态下被取消（此时用户已付款），系统需要自动调用微信支付退款接口为用户退款。
        //状态变更：取消成功后，需将订单状态修改为“已取消”。

        Orders ordersDB =  orderMapper.getById(id);
        // 校验订单是否存在
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        //订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
        if (ordersDB.getStatus() > 2) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }


        Orders orders = new Orders();
        orders.setId(ordersDB.getId());

        // 订单处于待接单状态下取消，需要进行退款
        if (ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            //调用微信支付退款接口

//            weChatPayUtil.refund(
//                    ordersDB.getNumber(), //商户订单号
//                    ordersDB.getNumber(), //商户退款单号
//                    new BigDecimal(0.01),//退款金额，单位 元
//                    new BigDecimal(0.01));//原订单金额

            // 我们假装退款成功了，直接修改状态即可
            //支付状态修改为 退款
            orders.setPayStatus(Orders.REFUND);
        }

        // 更新订单状态、取消原因、取消时间
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);


    }

    @Override
    public void repetition(Long id) {
        //1. 获取当前用户 ID：通过 BaseContext.getCurrentId() 获取。
        //2. 查询订单明细：根据订单 ID 调用 orderDetailMapper.getByOrderId(id) 获取商品列表。
        //3. 对象转换与封装：将每一个 OrderDetail 对象转换为 ShoppingCart 对象，并设置当前用户 ID 和创建时间。
        //4. 批量插入：调用 shoppingCartMapper.insertBatch() 写入数据库

        // 1. 查询当前用户id
        Long userId = BaseContext.getCurrentId();

        // 2. 根据订单id查询当前订单详情
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        // 3. 将订单详情对象转换为购物车对象
        List<ShoppingCart> shoppingCartList = new ArrayList<>();
        for (OrderDetail orderDetail : orderDetailList) {
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(orderDetail,shoppingCart,"id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());
            shoppingCartList.add(shoppingCart);
        }
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * 订单搜索
     * @param ordersPageQueryDTO
     * @return
     */
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        //业务逻辑：
        //    1. 调用 PageHelper.startPage 开启分页。
        //    2. 调用 orderMapper.pageQuery 获取分页后的 Orders 列表。
        //    3. 关键步骤：将 Orders 转换为 OrderVO。在转换过程中，需要查询订单明细，并将菜品名称与数量拼接成字符串（例如：getOrderDishesStr 方法）
        // 1. 开启分页
        PageHelper.startPage(ordersPageQueryDTO.getPage(),ordersPageQueryDTO.getPageSize());
        // 2. 调用Mapper进行条件查询
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);
        // 3. 将 Orders 转换为 OrderVO (为了封装 "订单菜品信息" 字符串)
        List<OrderVO> orderVOList = getOrderVOList(page);


        return new PageResult(page.getTotal(),orderVOList);
    }

    // 辅助方法：将 Page<Orders> 转换为 List<OrderVO>
    private List<OrderVO> getOrderVOList(Page<Orders> page) {
        List<OrderVO> orderVOList = new ArrayList<>();
        List<Orders> ordersList = page.getResult();

        if (!CollectionUtils.isEmpty(ordersList)) {
            for (Orders orders : ordersList) {
                // 1. 复制基本属性
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);

                // 2. 获取并拼接菜品信息字符串 (核心点)
                String orderDishes = getOrderDishesStr(orders);

                // 3. 封装到 VO
                orderVO.setOrderDishes(orderDishes);
                orderVOList.add(orderVO);
            }
        }
        return orderVOList;
    }

    // 辅助方法：根据订单ID获取菜品信息字符串
    private String getOrderDishesStr(Orders orders) {
        // 查询订单详情表 (order_detail)
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 将每一条明细拼接为字符串 (格式：菜品名*数量;)
        List<String> orderDishList = orderDetailList.stream().map(x -> {
            return x.getName() + "*" + x.getNumber() + ";";
        }).collect(Collectors.toList());

        // 使用 String.join 连接
        return String.join("", orderDishList);
    }

    /**
     * 各个状态的订单数量统计
     * @return
     */
    public OrderStatisticsVO statistics() {
        // 根据状态，分别查询出待接单、待派送、派送中的订单数量
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);

        // 将查询出的数据封装到orderStatisticsVO对象中响应
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    /**
     * 接单
     *
     * @param ordersConfirmDTO
     */
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders order = Orders.builder()
                .id(ordersConfirmDTO.getId())
                .status(Orders.CONFIRMED)
                .build();
        orderMapper.update(order);
    }

    /**
     * 拒单
     * @param ordersRejectionDTO
     */
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) {
        //业务逻辑：
        //1. 查询并校验订单：根据 ID 查询订单，校验订单是否存在且状态是否为“待接单”；若不符合条件，抛出 OrderBusinessException。
        //2. 退款处理：检查订单的支付状态，若为“已支付”，调用 weChatPayUtil.refund 发起退款。
        //3. 更新订单状态：构造一个 Orders 对象，设置 ID、状态为“已取消”、拒单原因以及取消时间，最后调用 orderMapper.update
        Orders ordersDB = orderMapper.getById(ordersRejectionDTO.getId());
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // 2. 支付状态判断 (模拟退款逻辑)
        Integer payStatus = ordersDB.getPayStatus();
        // 只有“已支付”的订单，取消时才需要修改支付状态为“退款”
        // 如果是“未支付”的订单，取消时支付状态应该保持“未支付”不变
        if (Orders.PAID.equals(payStatus)) {
// 这里注释掉了调用微信退款接口的代码---学习中用不了
//                  //用户已支付，需要退款
//            String refund = weChatPayUtil.refund(
//                    ordersDB.getNumber(),
//                    ordersDB.getNumber(),
//                    new BigDecimal(0.01),
//                    new BigDecimal(0.01));
//            log.info("申请退款：{}", refund);

            payStatus = Orders.REFUND;
        }

        // 拒单需要退款，根据订单id更新订单状态、拒单原因、取消时间
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());
        orders.setPayStatus(payStatus);
        orderMapper.update(orders);
    }


    /**
     * 取消订单
     * @param ordersCancelDTO
     */
    public void cancel(OrdersCancelDTO ordersCancelDTO) {
        //业务逻辑：
        //• 状态变更：操作成功后，订单状态必须修改为“已取消”。
        //• 填写原因：商家在取消时必须输入取消原因（cancelReason）。
        //• 退款处理：系统需检查订单支付状态。如果用户已经完成了支付（pay_status == 1），商家取消订单时，系统必须自动调用微信支付退款接口完成退款

        //1. 查询订单：调用 orderMapper.getById 获取订单详情。
        Orders ordersDB = orderMapper.getById(ordersCancelDTO.getId());
        //2. 支付校验与退款：检查 payStatus。若为已支付，调用 weChatPayUtil.refund 发起退款申请。
//        if (ordersDB.getPayStatus()==Orders.PAID){
//                        //用户已支付，需要退款
//            String refund = weChatPayUtil.refund(
//                    ordersDB.getNumber(),
//                    ordersDB.getNumber(),
//                    new BigDecimal(0.01),
//                    new BigDecimal(0.01));
//            log.info("申请退款：{}", refund);
//        }

        // 2. 支付状态判断 (模拟退款逻辑)
        Integer payStatus = ordersDB.getPayStatus();
        // 只有“已支付”的订单，取消时才需要修改支付状态为“退款”
        // 如果是“未支付”的订单，取消时支付状态应该保持“未支付”不变
        if (Orders.PAID.equals(payStatus)) {
// 这里注释掉了调用微信退款接口的代码---学习中用不了
//                  //用户已支付，需要退款
//            String refund = weChatPayUtil.refund(
//                    ordersDB.getNumber(),
//                    ordersDB.getNumber(),
//                    new BigDecimal(0.01),
//                    new BigDecimal(0.01));
//            log.info("申请退款：{}", refund);

            payStatus = Orders.REFUND;
        }

        //3. 更新数据库：封装 Orders 对象，设置状态为“已取消”，记录取消原因和当前时间，最后调用 orderMapper.update
        Orders order =Orders.builder()
                .id(ordersCancelDTO.getId())
                .status(Orders.CANCELLED)
                .cancelReason(ordersCancelDTO.getCancelReason())
                .payStatus(payStatus)   // 这里传入经过判断后的 payStatus
                .cancelTime(LocalDateTime.now())
                .build();
        orderMapper.update(order);
    }

    /**
     * 派送订单
     * @param id
     */
    public void delivery(Long id) {
        // 1. 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);
        //前置条件：只有状态为“待派送”（即商家 3已接单，状态码为 3）的订单才可以执行派送操作
        // 2. 校验订单是否存在，并且状态是否为 3 (待派送)   --订单不存在或者状态不是 3 (待派送)，抛异常
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.CONFIRMED) ){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // 3. 构造订单对象，更新状态为 4 (派送中)
        Orders order = Orders.builder()
                .id(id)
                .status(Orders.DELIVERY_IN_PROGRESS)
                .build();

        // 4. 调用mapper修改状态
        orderMapper.update(order);
    }

    /**
     * 完成订单
     * @param id
     */
    public void complete(Long id) {
        //业务逻辑：
        //1. 查询订单：调用 orderMapper.getById(id) 获取当前订单详情。
        //2. 状态校验：确认订单是否存在，且当前状态必须为 4 (DELIVERY_IN_PROGRESS)。若校验失败，抛出业务异常 ORDER_STATUS_ERROR。
        //3. 封装更新对象：创建一个新的 Orders 对象，设置 ID、目标状态为 5 (COMPLETED)，并记录当前时间为送达时间 (deliveryTime)。
        //4. 持久化：调用 orderMapper.update(orders) 更新数据库
        // 1. 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);
        // 2. 校验订单是否存在，并且状态是否为 4 (派送中)
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        // 3. 构造订单对象，更新状态为 5 (已完成)，并设置送达时间
        Orders order = Orders.builder()
                .id(id)
                .status(Orders.COMPLETED)
                .deliveryTime(LocalDateTime.now())
                .build();
        // 4. 执行更新
        orderMapper.update(order);
    }

    /**
     * 检查客户的收货地址是否超出配送范围
     * @param address
     */
    private void checkOutOfRange(String address) {
        Map map = new HashMap();
        map.put("address",shopAddress);
        map.put("output","json");
        map.put("ak",ak);

        //获取店铺的经纬度坐标
        String shopCoordinate = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3", map);

        JSONObject jsonObject = JSON.parseObject(shopCoordinate);
        if(!jsonObject.getString("status").equals("0")){
            throw new OrderBusinessException("店铺地址解析失败");
        }

        //数据解析
        JSONObject location = jsonObject.getJSONObject("result").getJSONObject("location");
        String lat = location.getString("lat");
        String lng = location.getString("lng");
        //店铺经纬度坐标
        String shopLngLat = lat + "," + lng;

        map.put("address",address);
        //获取用户收货地址的经纬度坐标
        String userCoordinate = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3", map);

        jsonObject = JSON.parseObject(userCoordinate);
        if(!jsonObject.getString("status").equals("0")){
            throw new OrderBusinessException("收货地址解析失败");
        }

        //数据解析
        location = jsonObject.getJSONObject("result").getJSONObject("location");
        lat = location.getString("lat");
        lng = location.getString("lng");
        //用户收货地址经纬度坐标
        String userLngLat = lat + "," + lng;

        map.put("origin",shopLngLat);
        map.put("destination",userLngLat);
        map.put("steps_info","0");

        //路线规划
        String json = HttpClientUtil.doGet("https://api.map.baidu.com/directionlite/v1/driving", map);

        jsonObject = JSON.parseObject(json);
        if(!jsonObject.getString("status").equals("0")){
            throw new OrderBusinessException("配送路线规划失败");
        }

        //数据解析
        JSONObject result = jsonObject.getJSONObject("result");
        JSONArray jsonArray = (JSONArray) result.get("routes");
        Integer distance = (Integer) ((JSONObject) jsonArray.get(0)).get("distance");

        if(distance > 5000){
            //配送距离超过5000米
            throw new OrderBusinessException("超出配送范围");
        }
    }


    /**
     * 催单
     * @param id
     */
    public void reminder(Long id) {
        Orders orderDB = orderMapper.getById(id);

        if (orderDB == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        //基于WebSocket实现催单
        Map map = new HashMap();
        map.put("type", 2);//2代表用户催单
        map.put("orderId", id);
        map.put("content", "订单号：" + orderDB.getNumber());
        webSocketServer.sendToAllClient(JSON.toJSONString(map));
    }
}