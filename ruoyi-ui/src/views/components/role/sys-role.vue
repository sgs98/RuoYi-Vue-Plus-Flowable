<template>
<el-dialog title="角色" :visible.sync="visible" v-if="visible" width="70%" append-to-body v-dialogDrag :close-on-click-modal="false">

  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" v-show="showSearch" :inline="true">
      <el-form-item label="角色名称" prop="roleName">
        <el-input
          v-model="queryParams.roleName"
          placeholder="请输入角色名称"
          clearable
          size="small"
          style="width: 240px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="权限字符" prop="roleKey">
        <el-input
          v-model="queryParams.roleKey"
          placeholder="请输入权限字符"
          clearable
          size="small"
          style="width: 240px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" ref="multipleTable" height="250px" border :data="roleList" :row-key="getRowKey" @selection-change="handleSelectionChange">
      <el-table-column type="selection" :reserve-selection="true" width="55" align="center" />
      <el-table-column label="角色编号" prop="roleId" :show-overflow-tooltip="true" />
      <el-table-column label="角色名称" prop="roleName" :show-overflow-tooltip="true" />
      <el-table-column label="权限字符" prop="roleKey" :show-overflow-tooltip="true"  />
      <el-table-column label="显示顺序" prop="roleSort" width="100" />
      <el-table-column label="创建时间" align="center" prop="createTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.createTime) }}</span>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total>0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <!-- 选中的角色 -->
    <div>
      <el-tag v-for="role in chooseRoleList" :key="role.roleId" style="margin:12px 2px 0px 2px"
      closable @close="handleCloseTag(role)" >{{role.roleName}} </el-tag>
    </div>
  </div>
  <div slot="footer" class="dialog-footer">
        <el-button size="small" type="primary" @click="confirmUser">确认</el-button>
        <el-button size="small" @click="visible=false">取 消</el-button>
  </div>
</el-dialog>
</template>

<script>
import { getRoleListByPage } from "@/api/workflow/workflowUser";
export default {
  name: "Role",
  props: {
    //回显的数据
    propRoleList: {
      type: Array,
      default:()=>[]
    }
  },
  data() {
    return {
      visible: false,
      // 遮罩层
      loading: true,
      // 导出遮罩层
      exportLoading: false,
      // 选中数组
      ids: [],
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 角色表格数据
      roleList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 日期范围
      dateRange: [],
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        roleName: undefined,
        roleKey: undefined,
        ids:[]
      },
      getRowKey(row) {
        return row.roleId
      },
      //选中的角色
      chooseRoleList: []
    };
  },
  watch: {
    visible(val) {
      if(val){
        this.$nextTick(()=>{
          this.$refs.multipleTable.clearSelection();
        })
        this.chooseRoleList = []
        if(this.propRoleList.length>0){
            this.queryParams.ids = this.propRoleList
        }
        this.getList();
      }
    }
  },
  methods: {
    /** 查询角色列表 */
    getList() {
      this.loading = true;
      getRoleListByPage(this.queryParams).then(response => {
          let res = response.data.page
          this.roleList = res.rows;
          this.total = res.total;
           //反选
          if(response.data.list){
            this.chooseRoleList = response.data.list
            response.data.list.forEach(row => {
              this.$refs.multipleTable.toggleRowSelection(row,true);
            })
          }
          this.loading = false;
        });
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.dateRange = [];
      this.resetForm("queryForm");
      this.handleQuery();
    },
    // 多选框选中数据
    handleSelectionChange(val) {
      if(this.multiple){
          this.chooseRoleList = val.filter((element,index,self)=>{
             return self.findIndex(x=>x.roleId===element.roleId) === index
          })
          val.forEach(u=>{
            if(!this.chooseRoleList.includes(u)){
              this.$refs.multipleTable.toggleRowSelection(u, false);
            }
          })
          this.queryParams.ids = this.chooseRoleList.map((item) => {return item.roleId});
        }else{
          this.chooseRoleList = val
          if (val.length > 1) {
            let delRow = val.shift();
            this.$refs.multipleTable.toggleRowSelection(delRow, false);
            this.queryParams.ids = this.chooseRoleList.map((item) => {return item.roleId});
          }
          if(val.length === 0){
            this.chooseRoleList = null
          }
        }
    },
    // 删除tag
    handleCloseTag(role,index){
       if(this.$refs.multipleTable.selection && this.$refs.multipleTable.selection.length > 0){
        this.$refs.multipleTable.selection.forEach((e,i)=>{
          if(role.roleId === e.roleId){
            this.$refs.multipleTable.selection.splice(i, 1);
          }
        })
      }
      this.chooseRoleList.splice(index, 1);
      this.$refs.multipleTable.toggleRowSelection(role, false)

      if(this.queryParams.ids && this.queryParams.ids.length > 0){
        this.queryParams.ids.forEach((roleId,i)=>{
          if(roleId === role.roleId){
            this.queryParams.ids.splice(i, 1);
          }
        })
      }
    },
    // 确认
    confirmUser(){
      if(this.chooseRoleList.length>0){
        this.$emit("confirmUser",this.chooseRoleList)
      }else{
        this.$modal.msgWarning("请选择角色！");
      }
    }
  }
};
</script>
<style scoped>
.app-container{
  height: 500px;
  overflow: auto;
}
</style>