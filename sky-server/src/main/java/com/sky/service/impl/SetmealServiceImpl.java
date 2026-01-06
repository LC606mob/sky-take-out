package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.BaseException;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetmealService;
import com.sky.vo.SetmealVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SetmealServiceImpl implements SetmealService {
    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private DishMapper dishMapper;
    @Transactional
    public void save(SetmealDTO setmealDTO) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO,setmeal);

        setmealMapper.insert(setmeal);

        Long setmealId = setmeal.getId();

        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        if(setmealDishes!=null&&setmealDishes.size()>0){
            for (SetmealDish setmealDish : setmealDishes) {
                setmealDish.setSetmealId(setmealId);
            }
        }
        setmealDishMapper.insertBatch(setmealDishes);
    }

    /**
     * 分页查询
     * @param setmealPageQueryDTO
     * @return
     */
    public PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO) {
        PageHelper.startPage(setmealPageQueryDTO.getPage(),setmealPageQueryDTO.getPageSize());

        Page<SetmealVO> page = setmealMapper.pageQuery(setmealPageQueryDTO);

        return new PageResult(page.getTotal(),page.getResult());
    }

    /**
     * 批量删除套餐
     * @param ids
     */
    @Transactional
    public void deleteBatch(List<Long> ids) {
        //判断当前套餐是否能够删除---是否存在起售中
        for (Long id : ids) {
            Setmeal setmeal = setmealMapper.getById(id);
            if (setmeal.getStatus() == StatusConstant.ENABLE){
                //起售中的套餐不能删除
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        }
        //根据套餐id集合批量删除套餐表中的套餐数据
        setmealMapper.deleteByIds(ids);
        //根据套餐id集合批量删除套餐菜品关系表中的数据
        setmealDishMapper.deleteBySetmealIds(ids);
    }

    /**
     * 根据id查询套餐和套餐菜品关系
     *
     * @param id
     * @return
     */
    public SetmealVO getById(Long id) {
        //根据id查询套餐数据
        Setmeal setmeal = setmealMapper.getById(id);
        //根据套餐id查询与套餐关联的套餐菜品关系表中的数据
        List<SetmealDish> setmealDishes = setmealDishMapper.getBySetmealId(id);
        //封装到SetmealVO
        SetmealVO setmealVO = new SetmealVO();
        BeanUtils.copyProperties(setmeal,setmealVO);
        setmealVO.setSetmealDishes(setmealDishes);

        return setmealVO;
    }
    /**
     * 修改套餐
     *
     * @param setmealDTO
     */
    @Transactional
    public void update(SetmealDTO setmealDTO) {
        //先修改setmeal表
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);
        setmealMapper.update(setmeal);
        //再修改setmealdish表，先删除再添加
        setmealDishMapper.deleteBySetmealId(setmealDTO.getId());

        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        // 第一步：先遍历一遍，给所有口味都打上 dishId 标签
        if (setmealDishes != null && setmealDishes.size() > 0) {
            // 第一步：先遍历一遍，给所有口味都打上 dishId 标签
            for (SetmealDish setmealDish : setmealDishes) {
                setmealDish.setSetmealId(setmealDTO.getId());
            }
            // 第二步：等大家都准备好了，再一次性批量插入（移到循环外面！）
            setmealDishMapper.insertBatch(setmealDishes);
        }
    }

    /**
     * 套餐起售、停售
     * @param status
     * @param id
     */
    public void startOrStop(Integer status, Long id) {
        //起售套餐前，要确保“套餐里所有菜品都在售”（否则套餐上架但里面菜品下架，逻辑矛盾）

        //根据 setmealId 查出其菜品列表（通过 setmeal_dish 关联 dish）
        // 如果是要起售套餐 (status = 1)
        if (status == StatusConstant.ENABLE) {
            // 1. 获取该套餐下所有菜品的状态列表
            List<Integer> dishStatusList = dishMapper.getDishStatusBySetmealId(id);

            // 2. 检查列表中是否包含 0 (停售状态)
            // 只要有一个菜品是停售的(0)，整个套餐就不能起售
            if (dishStatusList != null && dishStatusList.contains(StatusConstant.DISABLE)) {
                //若存在停售菜品：抛业务异常
                throw new BaseException(MessageConstant.SETMEAL_ENABLE_FAILED);
            }
        }
        //校验通过：更新 setmeal.status
        Setmeal setmeal=Setmeal.builder()
                .id(id)
                .status(status)
                .build();
        setmealMapper.update(setmeal);
    }
}
