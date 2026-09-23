-- Creation and lifecycle management stay on separate administrator pages.
UPDATE sys_menu SET menu_name='角色制作', remark='管理员制作官方角色；普通用户制作本人私有角色' WHERE menu_id=1100;
UPDATE sys_menu SET menu_name='虚拟角色与声音资产', remark='公共角色和官方声音的引用查看、下架、停用与删除；非平台用户权限角色' WHERE menu_id=1115;
