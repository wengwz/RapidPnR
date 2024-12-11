
import json
from pyvis.network import Network


def netlist_visualize(json_path:str):
    
    json_file = open(json_path, 'r')
    netlist_json = json.load(json_file)
    group_num = netlist_json['totalGroupNum']
    edge_num = netlist_json['totalEdgeNum']
    abstractGroups = netlist_json['abstractGroups']
    abstractEdges = netlist_json['abstractEdges']
    
    network = Network()
    
    for abstractGroup in abstractGroups:
        id = abstractGroup['id']
        weight = abstractGroup['primCellNum']
        size = weight / 50
        if size < 5:
            size = 5            
        network.add_node(id, label=f'grp-{id}', value=size)
        
    hyperedge_id = group_num
    for abstractEdge in abstractEdges:
        incidentGroupIds = abstractEdge['incidentGroupIds']
        weight = abstractEdge['weight']
        degree = abstractEdge['degree']
        assert degree == len(incidentGroupIds)
        
        for id1 in range(len(incidentGroupIds)):
            for id2 in range(id1 + 1, len(incidentGroupIds)):
                network.add_edge(incidentGroupIds[id1], incidentGroupIds[id2])
                
        # if degree == 2:
        #     network.add_edge(incidentGroupIds[0], incidentGroupIds[1])
        # else:
        #     network.add_node(hyperedge_id, value=50, label=f'edge-{hyperedge_id}', color="#dd4b39")
        #     for groupId in incidentGroupIds:
        #         network.add_edge(hyperedge_id, groupId)
        #     hyperedge_id += 1
        
    
    network.show("netlist_vis.html", notebook=False)
    
    return


if __name__ == "__main__":
    netlist_visualize("input.json")