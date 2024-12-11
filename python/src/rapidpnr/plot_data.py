
import matplotlib.pyplot as plt
import numpy as np

categories = ['blue\n-rdma', 'nvdla\n-small', 'nvdla\n-med', 'nvdla\n-large', 'ntt-small', 'ntt-large', 'corundum']
values1 = [285.7, 250.0, 188.6, 196.0, 555.5, 555.5, 277.7]
values2 = [277.7, 250.0, 188.6, 196.0, 555.5, 526.3, 263.1]

bar_width = 5

index = np.arange(len(categories))  # 每个类别的 x 位置
index = index * 15

# 创建两个柱子
plt.bar(index - bar_width / 2 - 0.25, values1, bar_width, label='Vivado', color='orange')
plt.bar(index + bar_width / 2 + 0.25, values2, bar_width, label='Our Flow', color='lightblue')

for i in range(len(categories)):
    ratio = values2[i] / values1[i]
    
    plt.text(index[i] + bar_width / 2 + 0.25 , values2[i],
             f'{ratio:.2f}', ha='center', va='bottom', fontsize=10, color='black')
    
# 添加标题和标签
#plt.xlabel('类别')
plt.ylabel('Frequency(MHz)')

# 设置 x 轴的刻度为类别
plt.xticks(index, categories)

# 添加图例
plt.legend()

# 显示图表
plt.show()