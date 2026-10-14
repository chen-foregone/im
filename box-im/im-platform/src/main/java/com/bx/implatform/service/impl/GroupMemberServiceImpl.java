package com.bx.implatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.entity.Group;
import com.bx.implatform.entity.GroupMember;
import com.bx.implatform.enums.GroupFanoutMode;
import com.bx.implatform.mapper.GroupMapper;
import com.bx.implatform.mapper.GroupMemberMapper;
import com.bx.implatform.service.GroupMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@CacheConfig(cacheNames = RedisKey.IM_CACHE_GROUP_MEMBER_ID)
public class GroupMemberServiceImpl extends ServiceImpl<GroupMemberMapper, GroupMember> implements GroupMemberService {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 直接注入 Mapper 而非 GroupService：
     * GroupServiceImpl 依赖 GroupMemberService，反向注入会形成循环依赖。
     */
    private final GroupMapper groupMapper;

    /** 群成员数跨过该值即翻成读扩散。取值依据见 docs/群聊瓶颈解决方案.md 3.7。 */
    @Value("${im.group.read-diffusion-threshold:2000}")
    private int readDiffusionThreshold;

    @CacheEvict(key = "#member.getGroupId()")
    @Override
    public boolean save(GroupMember member) {
        boolean saved = super.save(member);
        refreshGroupMemberCount(member.getGroupId());
        return saved;
    }

    @CacheEvict(key = "#groupId")
    @Override
    public boolean saveOrUpdateBatch(Long groupId, List<GroupMember> members) {
        Long version = getNextVersion();
        members.forEach(m -> m.setVersion(version));
        boolean saved = super.saveOrUpdateBatch(members);
        refreshGroupMemberCount(groupId);
        return saved;
    }

    /**
     * 成员变动后重算 owner 表的 member_count，并在跨过阈值时把群翻成读扩散。
     *
     * <p><b>为什么用 COUNT(*) 重算而不是增减计数</b>：该方法的上游
     * {@code saveOrUpdateBatch} 同时承担「新增成员」和「更新昵称/头像」两种语义，
     * 且 {@code saveOrUpdate} 对已存在的成员走的是 update，增减法无从分辨，会重复计数。
     * 重算虽多一条索引 COUNT（im_group_member 有 group_id 索引），但正确性优先，
     * 且成员变动是低频操作（非每条消息），不构成性能问题。</p>
     *
     * <p><b>为什么只升不降</b>：缩减群的业务规则尚未确定（见文档 3.7），
     * 这里刻意不自动翻回写扩散 —— 一旦翻回，正在按游标拉取的客户端会突然收不到信号。
     * 需要降级时用运维脚本显式改。</p>
     */
    private void refreshGroupMemberCount(Long groupId) {
        if (Objects.isNull(groupId)) {
            return;
        }
        LambdaQueryWrapper<GroupMember> countWrapper = Wrappers.lambdaQuery();
        countWrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getQuit, false);
        long memberCount = this.count(countWrapper);

        LambdaUpdateWrapper<Group> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(Group::getId, groupId);
        updateWrapper.set(Group::getMemberCount, (int) memberCount);
        if (memberCount > readDiffusionThreshold) {
            updateWrapper.set(Group::getFanoutMode, GroupFanoutMode.READ_DIFFUSION.getCode());
        }
        groupMapper.update(null, updateWrapper);
    }

    @Override
    public GroupMember findByGroupAndUserId(Long groupId, Long userId) {
        LambdaQueryWrapper<GroupMember> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMember::getGroupId, groupId);
        wrapper.eq(GroupMember::getUserId, userId);
        return this.getOne(wrapper);
    }

    @Override
    public List<GroupMember> findByUserId(Long userId) {
        LambdaQueryWrapper<GroupMember> memberWrapper = Wrappers.lambdaQuery();
        memberWrapper.eq(GroupMember::getUserId, userId).eq(GroupMember::getQuit, false);
        return this.list(memberWrapper);
    }


    @Override
    public List<GroupMember> findByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<GroupMember> wrapper = Wrappers.lambdaQuery();
        wrapper.in(GroupMember::getUserId, userIds).eq(GroupMember::getQuit, false);
        return this.list(wrapper);
    }

    @Override
    public List<GroupMember> findByGroupAndUserIds(Long groupId, List<Long> userIds) {
        LambdaQueryWrapper<GroupMember> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMember::getGroupId, groupId);
        wrapper.in(GroupMember::getUserId, userIds);
        return this.list(wrapper);
    }

    @Override
    public List<GroupMember> findQuitMembers(Long userId, Date minQuitTime) {
        LambdaQueryWrapper<GroupMember> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMember::getUserId, userId);
        wrapper.eq(GroupMember::getQuit, true);
        wrapper.ge(GroupMember::getQuitTime, minQuitTime);
        return this.list(wrapper);
    }

    @Override
    public List<GroupMember> findByGroupId(Long groupId, Long version) {
        LambdaQueryWrapper<GroupMember> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMember::getGroupId, groupId);
        wrapper.gt(version > 0, GroupMember::getVersion, version);
        return this.list(wrapper);
    }

    @Cacheable(key = "#groupId")
    @Override
    public List<Long> findUserIdsByGroupId(Long groupId) {
        LambdaQueryWrapper<GroupMember> memberWrapper = Wrappers.lambdaQuery();
        memberWrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getQuit, false)
            .select(GroupMember::getUserId);
        List<GroupMember> members = this.list(memberWrapper);
        return members.stream().map(GroupMember::getUserId).collect(Collectors.toList());
    }

    @CacheEvict(key = "#groupId")
    @Override
    public void removeByGroupId(Long groupId) {
        Long version = getNextVersion();
        LambdaUpdateWrapper<GroupMember> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(GroupMember::getGroupId, groupId);
        wrapper.eq(GroupMember::getQuit, false);
        wrapper.set(GroupMember::getQuit, true);
        wrapper.set(GroupMember::getQuitTime, new Date());
        wrapper.set(GroupMember::getVersion, version);
        this.update(wrapper);
        refreshGroupMemberCount(groupId);
    }

    @CacheEvict(key = "#groupId")
    @Override
    public void removeByGroupAndUserId(Long groupId, Long userId) {
        Long version = getNextVersion();
        LambdaUpdateWrapper<GroupMember> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(GroupMember::getGroupId, groupId);
        wrapper.eq(GroupMember::getUserId, userId);
        wrapper.eq(GroupMember::getQuit, false);
        wrapper.set(GroupMember::getQuit, true);
        wrapper.set(GroupMember::getQuitTime, new Date());
        wrapper.set(GroupMember::getVersion, version);
        this.update(wrapper);
        refreshGroupMemberCount(groupId);
    }

    @CacheEvict(key = "#groupId")
    @Override
    public void removeByGroupAndUserIds(Long groupId, List<Long> userIds) {
        Long version = getNextVersion();
        LambdaUpdateWrapper<GroupMember> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(GroupMember::getGroupId, groupId);
        wrapper.eq(GroupMember::getQuit, false);
        wrapper.in(GroupMember::getUserId, userIds);
        wrapper.set(GroupMember::getQuit, true);
        wrapper.set(GroupMember::getQuitTime, new Date());
        wrapper.set(GroupMember::getVersion, version);
        this.update(wrapper);
        refreshGroupMemberCount(groupId);
    }

    @Override
    public Boolean isInGroup(Long groupId, List<Long> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return true;
        }
        LambdaQueryWrapper<GroupMember> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMember::getGroupId, groupId);
        wrapper.eq(GroupMember::getQuit, false);
        wrapper.in(GroupMember::getUserId, userIds);
        return userIds.size() == this.count(wrapper);
    }

    @Override
    public void setDnd(Long groupId, Long userId, Boolean isDnd) {
        Long version = getNextVersion();
        LambdaUpdateWrapper<GroupMember> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(GroupMember::getGroupId, groupId);
        wrapper.eq(GroupMember::getUserId, userId);
        wrapper.set(GroupMember::getIsDnd, isDnd);
        wrapper.set(GroupMember::getVersion, version);
        this.update(wrapper);
    }

    @Override
    public void setTop(Long groupId, Long userId, Boolean isTop) {
        Long version = getNextVersion();
        LambdaUpdateWrapper<GroupMember> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(GroupMember::getGroupId, groupId);
        wrapper.eq(GroupMember::getUserId, userId);
        wrapper.set(GroupMember::getIsTop, isTop);
        wrapper.set(GroupMember::getVersion, version);
        this.update(wrapper);
    }

    @CacheEvict(key = "#member.getGroupId()")
    @Override
    public boolean saveOrUpdate(GroupMember member) {
        member.setVersion(getNextVersion());
        boolean saved = super.saveOrUpdate(member);
        refreshGroupMemberCount(member.getGroupId());
        return saved;
    }

    @Override
    public Long getNextVersion() {
        String key = RedisKey.IM_GROUP_MEMBER_MAX_VERSION;
        if (redisTemplate.hasKey(key)) {
            return redisTemplate.opsForValue().increment(key);
        } else {
            LambdaQueryWrapper<GroupMember> wrapper = Wrappers.lambdaQuery();
            wrapper.orderByDesc(GroupMember::getVersion);
            wrapper.last("limit 1");
            GroupMember member = this.getOne(wrapper);
            Long version = Objects.isNull(member) ? 1 : member.getVersion() + 1;
            redisTemplate.opsForValue().set(key, version);
            return version;
        }
    }
}
