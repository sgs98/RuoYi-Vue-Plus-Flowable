<template>
  <div>
    <div style="height: 45px;margin-top: -30px;">
      <el-button v-show="parentTaskId===null" :loading="buttonLoading" size="small" type="primary" @click="submitForm">提交</el-button>
      <el-button @click="bpmnProcess" size="small" v-if="processInstanceId">流程进度</el-button>
      <el-button @click="bpmnRecord" size="small" v-if="processInstanceId">审批意见</el-button>
      <el-button :loading="buttonLoading" size="small" @click="submitCallback">关闭</el-button>
    </div>
    <el-tabs type="border-card">
        <el-tab-pane label="业务单据" class="container-tab">
          <el-form ref="form" :model="form" :rules="rules" label-width="120px">
            <el-form-item label="申请人用户名" prop="username">
              <el-input v-model="form.username" placeholder="请输入申请人用户名" />
            </el-form-item>
            <el-form-item label="请假时长" v-if="fieldMap && fieldMap.duration && fieldMap.duration.hidden" prop="duration" :rules="[{ required: fieldMap && fieldMap.duration && fieldMap.duration.required, message: fieldMap && fieldMap.duration && fieldMap.duration.message, trigger: 'blur' }]">
              <el-input :disabled="fieldMap && fieldMap.duration && fieldMap.duration.edit" v-model="form.duration" placeholder="请输入请假时长，单位：天" />
            </el-form-item>
            <el-form-item label="工作委托人" v-if="fieldMap && fieldMap.principal && fieldMap.principal.hidden" prop="principal" :rules="[{ required: fieldMap && fieldMap.principal && fieldMap.principal.required, message: fieldMap && fieldMap.principal && fieldMap.principal.message, trigger: 'blur' }]">
              <el-input :disabled="fieldMap && fieldMap.principal && fieldMap.principal.edit" v-model="form.principal" placeholder="请输入工作委托人" />
            </el-form-item>
            <el-form-item label="联系电话" prop="contactPhone">
              <el-input v-model="form.contactPhone" placeholder="请输入联系电话" />
            </el-form-item>
            <el-form-item label="请假类型" prop="leaveType">
              <el-select style="width:100%" v-model="form.leaveType" placeholder="请选择请假类型">
                <el-option
                  v-for="dict in dict.type.bs_leave_type"
                  :key="dict.value"
                  :label="dict.label"
                  :value="parseInt(dict.value)"
                ></el-option>
              </el-select>
            </el-form-item>
            <el-form-item label="标题" prop="title">
              <el-input v-model="form.title" placeholder="请输入标题" />
            </el-form-item>
            <el-form-item label="请假原因" prop="leaveReason">
              <el-input v-model="form.leaveReason" type="textarea" placeholder="请输入内容" />
            </el-form-item>
            <el-form-item label="请假开始时间" prop="startDate">
              <el-date-picker clearable size="small"
                style="width:100%"
                v-model="form.startDate"
                type="datetime"
                value-format="yyyy-MM-dd HH:mm:ss"
                placeholder="选择请假开始时间">
              </el-date-picker>
            </el-form-item>
            <el-form-item label="请假结束时间" prop="endDate">
              <el-date-picker style="width:100%" clearable size="small"
                v-model="form.endDate"
                type="datetime"
                value-format="yyyy-MM-dd HH:mm:ss"
                placeholder="选择请假结束时间">
              </el-date-picker>
            </el-form-item>
          </el-form>
          <!-- 工作流开始 -->
          <verify ref="verifyRef" :taskId="taskId" @submitCallback="submitCallback"
          :taskVariables="taskVariables" :sendMessage="sendMessage"></verify>
          <!-- 流程进度 -->
          <HistoryBpmnDialog ref="historyBpmnRef"/>
          <!-- 审批意见 -->
          <HistoryRecordDialog ref="historyRecordRef"/>
          <!-- 工作流结束 -->
        </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import { getLeaveByTaskId} from "@/api/demo/leave";
import verify from "@/components/Process/Verify";
import HistoryBpmnDialog from "@/components/Process/HistoryBpmnDialog";
import HistoryRecordDialog from "@/components/Process/HistoryRecordDialog";
export default {
  name: "Leave",
  dicts: ['bs_leave_type'],
  props: {
      businessKey: String, // 业务唯一标识
      parentTaskId: String, // 父级任务id
      taskId: String // 任务id
  },
  components: {
      verify,
      HistoryRecordDialog,
      HistoryBpmnDialog
  },
  data() {
    return {
      // 按钮loading
      buttonLoading: false,
      // 遮罩层
      loading: true,
      // 请假业务表格数据
      leaveList: [],
      // 表单参数
      form: {},
      // 表单校验
      rules: {
       
      },
      taskVariables: undefined,
      //消息提醒
      sendMessage: {},
      processInstanceId:undefined,
      fieldMap:{}
    };
  },
  watch: {
      businessKey: {
          immediate: true, // 很重要！！！
          handler (newVal) {
              if(newVal) {
                  this.getById()
              }
          }
      }
  },
  methods: {
    //流程进度
    bpmnProcess(){
      this.$refs.historyBpmnRef.init(this.processInstanceId)
    },
    //审批意见
    bpmnRecord(){
      this.$refs.historyRecordRef.init(this.processInstanceId)
    },
    submitCallback(){
      this.$emit("closeForm")
    },
    async getById() {
        const {data} = await getLeaveByTaskId(this.businessKey,this.taskId)
        this.form = data;
        if(this.form.actNodeAssignee && this.form.actNodeAssignee.fieldMap){
          this.fieldMap = this.form.actNodeAssignee.fieldMap
        }
        this.processInstanceId = data.actBusinessStatus.processInstanceId
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          getLeaveByTaskId(this.businessKey,this.taskId).then(response => {
              this.taskVariables = {
                    entity: response.data,
                    userId :1
              };
              this.sendMessage = {
                title:'请假申请',
                messageContent:'单据【'+this.form.id+"】申请"
              }
          });
          this.$refs.verifyRef.visible = true
          this.$refs.verifyRef.reset()
        }
      })
    }
  }
};
</script>
<style scoped>
.container-tab{
    height: calc(100vh - 230px);
    overflow-y: auto;
    padding: 10px;
}
</style>
