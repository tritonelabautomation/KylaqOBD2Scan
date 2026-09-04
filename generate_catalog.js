const fs = require('fs');

const engines = [
  // VW / Skoda
  { id: "e_vw_10tsi", name: "1.0 TSI", code: "EA211", displacementCc: 999, cylinders: 3, aspiration: "TURBOCHARGED", fuelType: "PETROL", powerPs: 115, torqueNm: 178 },
  { id: "e_vw_15tsi", name: "1.5 TSI", code: "EA211 Evo", displacementCc: 1498, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "PETROL", powerPs: 150, torqueNm: 250 },
  { id: "e_vw_12tsi", name: "1.2 TSI", code: "EA111", displacementCc: 1197, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "PETROL", powerPs: 105, torqueNm: 175 },
  { id: "e_vw_15tdi", name: "1.5 TDI", code: "EA189", displacementCc: 1498, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 110, torqueNm: 250 },
  
  // Maruti Suzuki
  { id: "e_ms_k10c", name: "K10C 1.0L", code: "K10C", displacementCc: 998, cylinders: 3, aspiration: "NATURALLY_ASPIRATED", fuelType: "PETROL", powerPs: 67, torqueNm: 89 },
  { id: "e_ms_k12n", name: "K12N 1.2L", code: "K12N", displacementCc: 1197, cylinders: 4, aspiration: "NATURALLY_ASPIRATED", fuelType: "PETROL", powerPs: 90, torqueNm: 113 },
  { id: "e_ms_k15c", name: "K15C 1.5L", code: "K15C", displacementCc: 1462, cylinders: 4, aspiration: "NATURALLY_ASPIRATED", fuelType: "MILD_HYBRID", powerPs: 103, torqueNm: 137 },
  { id: "e_ms_ddis200", name: "1.3 DDiS", code: "DDiS 200", displacementCc: 1248, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 90, torqueNm: 200 },
  { id: "e_ms_k10c_cng", name: "K10C 1.0L CNG", code: "K10C", displacementCc: 998, cylinders: 3, aspiration: "NATURALLY_ASPIRATED", fuelType: "CNG", powerPs: 57, torqueNm: 82 },
  
  // Hyundai / Kia
  { id: "e_hy_12kappa", name: "1.2 Kappa", code: "Kappa", displacementCc: 1197, cylinders: 4, aspiration: "NATURALLY_ASPIRATED", fuelType: "PETROL", powerPs: 83, torqueNm: 114 },
  { id: "e_hy_15mpi", name: "1.5 MPi", code: "Smartstream G1.5", displacementCc: 1497, cylinders: 4, aspiration: "NATURALLY_ASPIRATED", fuelType: "PETROL", powerPs: 115, torqueNm: 144 },
  { id: "e_hy_15crdi", name: "1.5 CRDi", code: "U2", displacementCc: 1493, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 116, torqueNm: 250 },
  { id: "e_hy_10tgdi", name: "1.0 T-GDi", code: "Kappa II", displacementCc: 998, cylinders: 3, aspiration: "TURBOCHARGED", fuelType: "PETROL", powerPs: 120, torqueNm: 172 },
  { id: "e_hy_15tgdi", name: "1.5 T-GDi", code: "Smartstream G1.5T", displacementCc: 1482, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "PETROL", powerPs: 160, torqueNm: 253 },
  { id: "e_hy_14crdi", name: "1.4 CRDi", code: "U2 1.4", displacementCc: 1396, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 90, torqueNm: 220 },
  { id: "e_hy_16crdi", name: "1.6 CRDi", code: "U2 1.6", displacementCc: 1582, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 128, torqueNm: 260 },

  // Tata Motors
  { id: "e_tata_12revotron", name: "1.2L Revotron", code: "Revotron", displacementCc: 1199, cylinders: 3, aspiration: "NATURALLY_ASPIRATED", fuelType: "PETROL", powerPs: 86, torqueNm: 113 },
  { id: "e_tata_12revotron_t", name: "1.2L Turbo Revotron", code: "Revotron", displacementCc: 1199, cylinders: 3, aspiration: "TURBOCHARGED", fuelType: "PETROL", powerPs: 120, torqueNm: 170 },
  { id: "e_tata_15revotorq", name: "1.5L Revotorq", code: "Revotorq", displacementCc: 1497, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 115, torqueNm: 260 },
  { id: "e_tata_20kryotec", name: "2.0L Kryotec", code: "Kryotec", displacementCc: 1956, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 170, torqueNm: 350 },
  { id: "e_tata_ev_z", name: "Ziptron EV", code: "Ziptron", displacementCc: null, cylinders: null, aspiration: null, fuelType: "ELECTRIC", powerPs: 129, torqueNm: 245 },

  // Mahindra
  { id: "e_mh_mhawk130", name: "2.2L mHawk 130", code: "mHawk", displacementCc: 2184, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 130, torqueNm: 300 },
  { id: "e_mh_mhawk175", name: "2.2L mHawk 175", code: "mHawk", displacementCc: 2184, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 175, torqueNm: 400 },
  { id: "e_mh_mstallion150", name: "2.0L mStallion", code: "mStallion", displacementCc: 1997, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "PETROL", powerPs: 150, torqueNm: 300 },
  { id: "e_mh_mstallion200", name: "2.0L mStallion", code: "mStallion", displacementCc: 1997, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "PETROL", powerPs: 200, torqueNm: 380 },
  { id: "e_mh_mhawk115", name: "1.5L mHawk", code: "mHawk", displacementCc: 1497, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 115, torqueNm: 300 },

  // Toyota / Honda
  { id: "e_toyota_15petrol", name: "1.5L Petrol", code: "K15C", displacementCc: 1462, cylinders: 4, aspiration: "NATURALLY_ASPIRATED", fuelType: "MILD_HYBRID", powerPs: 103, torqueNm: 137 },
  { id: "e_toyota_15hybrid", name: "1.5L Strong Hybrid", code: "M15A-FXE", displacementCc: 1490, cylinders: 3, aspiration: "NATURALLY_ASPIRATED", fuelType: "STRONG_HYBRID", powerPs: 116, torqueNm: 141 },
  { id: "e_toyota_24diesel", name: "2.4L Diesel", code: "2GD-FTV", displacementCc: 2393, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 150, torqueNm: 343 },
  { id: "e_toyota_28diesel", name: "2.8L Diesel", code: "1GD-FTV", displacementCc: 2755, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 204, torqueNm: 500 },
  { id: "e_toyota_27petrol", name: "2.7L Petrol", code: "2TR-FE", displacementCc: 2694, cylinders: 4, aspiration: "NATURALLY_ASPIRATED", fuelType: "PETROL", powerPs: 166, torqueNm: 245 },
  
  { id: "e_honda_12ivtec", name: "1.2 i-VTEC", code: "L12B", displacementCc: 1199, cylinders: 4, aspiration: "NATURALLY_ASPIRATED", fuelType: "PETROL", powerPs: 90, torqueNm: 110 },
  { id: "e_honda_15ivtec", name: "1.5 i-VTEC", code: "L15B", displacementCc: 1498, cylinders: 4, aspiration: "NATURALLY_ASPIRATED", fuelType: "PETROL", powerPs: 121, torqueNm: 145 },
  { id: "e_honda_15idtec", name: "1.5 i-DTEC", code: "Earth Dreams", displacementCc: 1498, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 100, torqueNm: 200 },
  { id: "e_honda_15hybrid", name: "1.5 e:HEV", code: "LEB", displacementCc: 1498, cylinders: 4, aspiration: "NATURALLY_ASPIRATED", fuelType: "STRONG_HYBRID", powerPs: 126, torqueNm: 253 },
  
  // Renault / Nissan
  { id: "e_rn_10na", name: "1.0 Energy", code: "BR10", displacementCc: 999, cylinders: 3, aspiration: "NATURALLY_ASPIRATED", fuelType: "PETROL", powerPs: 72, torqueNm: 96 },
  { id: "e_rn_10turbo", name: "1.0 Turbo", code: "HR10DET", displacementCc: 999, cylinders: 3, aspiration: "TURBOCHARGED", fuelType: "PETROL", powerPs: 100, torqueNm: 160 },
  { id: "e_rn_15dci", name: "1.5 dCi", code: "K9K", displacementCc: 1461, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 110, torqueNm: 245 },
  { id: "e_rn_13turbo", name: "1.3 Turbo", code: "HR13DDT", displacementCc: 1330, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "PETROL", powerPs: 156, torqueNm: 254 },

  // Jeep
  { id: "e_jeep_20diesel", name: "2.0 Multijet II", code: "Multijet", displacementCc: 1956, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 170, torqueNm: 350 },
  { id: "e_jeep_14petrol", name: "1.4 Multiair", code: "Multiair", displacementCc: 1368, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "PETROL", powerPs: 163, torqueNm: 250 },
  
  // MG
  { id: "e_mg_15turbo", name: "1.5 Turbo", code: "", displacementCc: 1498, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "PETROL", powerPs: 143, torqueNm: 250 },
  { id: "e_mg_20diesel", name: "2.0 Diesel", code: "Multijet", displacementCc: 1956, cylinders: 4, aspiration: "TURBOCHARGED", fuelType: "DIESEL", powerPs: 170, torqueNm: 350 },
  { id: "e_mg_ev", name: "ZS EV Motor", code: "", displacementCc: null, cylinders: null, aspiration: null, fuelType: "ELECTRIC", powerPs: 176, torqueNm: 280 }
];

const transmissions = [
  { id: "t_mt5", name: "5-Speed MT", type: "MANUAL", gearCount: 5 },
  { id: "t_mt6", name: "6-Speed MT", type: "MANUAL", gearCount: 6 },
  { id: "t_at6", name: "6-Speed AT", type: "TORQUE_CONVERTER", gearCount: 6 },
  { id: "t_at8", name: "8-Speed AT", type: "TORQUE_CONVERTER", gearCount: 8 },
  { id: "t_at9", name: "9-Speed AT", type: "TORQUE_CONVERTER", gearCount: 9 },
  { id: "t_cvt", name: "CVT", type: "CVT", gearCount: null },
  { id: "t_ecvt", name: "e-CVT", type: "CVT", gearCount: null },
  { id: "t_dct7", name: "7-Speed DCT", type: "DCT", gearCount: 7 },
  { id: "t_dsg7", name: "7-Speed DSG", type: "DCT", gearCount: 7 },
  { id: "t_dsg6", name: "6-Speed DSG", type: "DCT", gearCount: 6 },
  { id: "t_amt5", name: "5-Speed AMT", type: "AMT", gearCount: 5 },
  { id: "t_amt6", name: "6-Speed AMT", type: "AMT", gearCount: 6 },
  { id: "t_imt6", name: "6-Speed iMT", type: "IMT", gearCount: 6 },
  { id: "t_ev1", name: "Single Speed", type: "ELECTRIC_SINGLE_SPEED", gearCount: 1 }
];

const manufacturers = [
  {
    id: "maruti_suzuki",
    name: "Maruti Suzuki",
    models: [
      {
        id: "ms_swift", name: "Swift", isCurrent: true,
        generations: [
          {
            id: "ms_swift_gen3", name: "Gen 3", startYear: 2018, endYear: 2024,
            variants: [
              { id: "ms_swift_gen3_vxi_mt", name: "VXi MT", engineId: "e_ms_k12n", transmissionId: "t_mt5", bodyType: "HATCHBACK", drivetrain: "FWD" },
              { id: "ms_swift_gen3_vxi_amt", name: "VXi AMT", engineId: "e_ms_k12n", transmissionId: "t_amt5", bodyType: "HATCHBACK", drivetrain: "FWD" }
            ]
          },
          {
            id: "ms_swift_gen4", name: "Gen 4", startYear: 2024, endYear: null,
            variants: [
              { id: "ms_swift_gen4_vxi_mt", name: "VXi MT", engineId: "e_ms_k12n", transmissionId: "t_mt5", bodyType: "HATCHBACK", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "ms_baleno", name: "Baleno", isCurrent: true,
        generations: [
          {
            id: "ms_baleno_gen1", name: "Gen 1 Facelift", startYear: 2022, endYear: null,
            variants: [
              { id: "ms_baleno_zeta_mt", name: "Zeta MT", engineId: "e_ms_k12n", transmissionId: "t_mt5", bodyType: "HATCHBACK", drivetrain: "FWD" },
              { id: "ms_baleno_zeta_amt", name: "Zeta AMT", engineId: "e_ms_k12n", transmissionId: "t_amt5", bodyType: "HATCHBACK", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "ms_brezza", name: "Brezza", isCurrent: true,
        generations: [
          {
            id: "ms_brezza_gen2", name: "Gen 2", startYear: 2022, endYear: null,
            variants: [
              { id: "ms_brezza_zxi_mt", name: "ZXi MT", engineId: "e_ms_k15c", transmissionId: "t_mt5", bodyType: "COMPACT_SUV", drivetrain: "FWD" },
              { id: "ms_brezza_zxi_at", name: "ZXi AT", engineId: "e_ms_k15c", transmissionId: "t_at6", bodyType: "COMPACT_SUV", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "ms_grand_vitara", name: "Grand Vitara", isCurrent: true,
        generations: [
          {
            id: "ms_gv_gen1", name: "Gen 1", startYear: 2022, endYear: null,
            variants: [
              { id: "ms_gv_mild_mt", name: "Smart Hybrid Zeta MT", engineId: "e_ms_k15c", transmissionId: "t_mt5", bodyType: "SUV", drivetrain: "FWD" },
              { id: "ms_gv_mild_at", name: "Smart Hybrid Zeta AT", engineId: "e_ms_k15c", transmissionId: "t_at6", bodyType: "SUV", drivetrain: "FWD" },
              { id: "ms_gv_strong", name: "Intelligent Hybrid Zeta+", engineId: "e_toyota_15hybrid", transmissionId: "t_ecvt", bodyType: "SUV", drivetrain: "FWD" },
              { id: "ms_gv_awd", name: "Smart Hybrid Alpha AWD", engineId: "e_ms_k15c", transmissionId: "t_mt5", bodyType: "SUV", drivetrain: "AWD" }
            ]
          }
        ]
      }
    ]
  },
  {
    id: "hyundai",
    name: "Hyundai",
    models: [
      {
        id: "hy_creta", name: "Creta", isCurrent: true,
        generations: [
          {
            id: "hy_creta_gen2_fl", name: "Gen 2 Facelift", startYear: 2024, endYear: null,
            variants: [
              { id: "hy_creta_15p_mt", name: "1.5 MPi SX MT", engineId: "e_hy_15mpi", transmissionId: "t_mt6", bodyType: "SUV", drivetrain: "FWD" },
              { id: "hy_creta_15p_ivt", name: "1.5 MPi SX IVT", engineId: "e_hy_15mpi", transmissionId: "t_cvt", bodyType: "SUV", drivetrain: "FWD" },
              { id: "hy_creta_15d_mt", name: "1.5 CRDi SX MT", engineId: "e_hy_15crdi", transmissionId: "t_mt6", bodyType: "SUV", drivetrain: "FWD" },
              { id: "hy_creta_15d_at", name: "1.5 CRDi SX(O) AT", engineId: "e_hy_15crdi", transmissionId: "t_at6", bodyType: "SUV", drivetrain: "FWD" },
              { id: "hy_creta_15t_dct", name: "1.5 T-GDi SX(O) DCT", engineId: "e_hy_15tgdi", transmissionId: "t_dct7", bodyType: "SUV", drivetrain: "FWD" }
            ]
          },
          {
            id: "hy_creta_gen1", name: "Gen 1", startYear: 2015, endYear: 2020,
            variants: [
              { id: "hy_creta_16d_sx", name: "1.6 CRDi SX", engineId: "e_hy_16crdi", transmissionId: "t_mt6", bodyType: "SUV", drivetrain: "FWD" },
              { id: "hy_creta_14d_s", name: "1.4 CRDi S", engineId: "e_hy_14crdi", transmissionId: "t_mt6", bodyType: "SUV", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "hy_i20", name: "i20", isCurrent: true,
        generations: [
          {
            id: "hy_i20_gen3", name: "Gen 3", startYear: 2020, endYear: null,
            variants: [
              { id: "hy_i20_12p_mt", name: "1.2 Kappa Asta MT", engineId: "e_hy_12kappa", transmissionId: "t_mt5", bodyType: "HATCHBACK", drivetrain: "FWD" },
              { id: "hy_i20_10t_dct", name: "1.0 T-GDi Asta DCT", engineId: "e_hy_10tgdi", transmissionId: "t_dct7", bodyType: "HATCHBACK", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "hy_verna", name: "Verna", isCurrent: true,
        generations: [
          {
            id: "hy_verna_gen6", name: "Gen 6", startYear: 2023, endYear: null,
            variants: [
              { id: "hy_verna_15p_mt", name: "1.5 MPi SX MT", engineId: "e_hy_15mpi", transmissionId: "t_mt6", bodyType: "SEDAN", drivetrain: "FWD" },
              { id: "hy_verna_15t_dct", name: "1.5 T-GDi SX(O) DCT", engineId: "e_hy_15tgdi", transmissionId: "t_dct7", bodyType: "SEDAN", drivetrain: "FWD" }
            ]
          }
        ]
      }
    ]
  },
  {
    id: "kia",
    name: "Kia",
    models: [
      {
        id: "kia_seltos", name: "Seltos", isCurrent: true,
        generations: [
          {
            id: "kia_seltos_gen1_fl", name: "Gen 1 Facelift", startYear: 2023, endYear: null,
            variants: [
              { id: "kia_seltos_15p_mt", name: "HTX 1.5 MT", engineId: "e_hy_15mpi", transmissionId: "t_mt6", bodyType: "SUV", drivetrain: "FWD" },
              { id: "kia_seltos_15t_dct", name: "GTX+ 1.5 T-GDi DCT", engineId: "e_hy_15tgdi", transmissionId: "t_dct7", bodyType: "SUV", drivetrain: "FWD" },
              { id: "kia_seltos_15d_at", name: "HTX 1.5 CRDi AT", engineId: "e_hy_15crdi", transmissionId: "t_at6", bodyType: "SUV", drivetrain: "FWD" }
            ]
          }
        ]
      }
    ]
  },
  {
    id: "tata",
    name: "Tata Motors",
    models: [
      {
        id: "tata_nexon", name: "Nexon", isCurrent: true,
        generations: [
          {
            id: "tata_nexon_gen1_fl2", name: "Gen 1 Facelift 2", startYear: 2023, endYear: null,
            variants: [
              { id: "tata_nexon_p_mt", name: "Creative 1.2 Revotron MT", engineId: "e_tata_12revotron_t", transmissionId: "t_mt6", bodyType: "COMPACT_SUV", drivetrain: "FWD" },
              { id: "tata_nexon_p_dca", name: "Fearless 1.2 Revotron DCA", engineId: "e_tata_12revotron_t", transmissionId: "t_dct7", bodyType: "COMPACT_SUV", drivetrain: "FWD" },
              { id: "tata_nexon_d_amt", name: "Creative 1.5 Revotorq AMT", engineId: "e_tata_15revotorq", transmissionId: "t_amt6", bodyType: "COMPACT_SUV", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "tata_harrier", name: "Harrier", isCurrent: true,
        generations: [
          {
            id: "tata_harrier_gen1_fl", name: "Gen 1 Facelift", startYear: 2023, endYear: null,
            variants: [
              { id: "tata_harrier_d_mt", name: "Adventure 2.0 Kryotec MT", engineId: "e_tata_20kryotec", transmissionId: "t_mt6", bodyType: "SUV", drivetrain: "FWD" },
              { id: "tata_harrier_d_at", name: "Fearless 2.0 Kryotec AT", engineId: "e_tata_20kryotec", transmissionId: "t_at6", bodyType: "SUV", drivetrain: "FWD" }
            ]
          }
        ]
      }
    ]
  },
  {
    id: "mahindra",
    name: "Mahindra",
    models: [
      {
        id: "mh_xuv700", name: "XUV700", isCurrent: true,
        generations: [
          {
            id: "mh_xuv700_gen1", name: "Gen 1", startYear: 2021, endYear: null,
            variants: [
              { id: "mh_xuv700_p_mt", name: "AX5 Petrol MT", engineId: "e_mh_mstallion200", transmissionId: "t_mt6", bodyType: "SUV", drivetrain: "FWD" },
              { id: "mh_xuv700_d_at", name: "AX7 Diesel AT", engineId: "e_mh_mhawk175", transmissionId: "t_at6", bodyType: "SUV", drivetrain: "FWD" },
              { id: "mh_xuv700_d_awd", name: "AX7 Diesel AT AWD", engineId: "e_mh_mhawk175", transmissionId: "t_at6", bodyType: "SUV", drivetrain: "AWD" }
            ]
          }
        ]
      },
      {
        id: "mh_thar", name: "Thar", isCurrent: true,
        generations: [
          {
            id: "mh_thar_gen2", name: "Gen 2", startYear: 2020, endYear: null,
            variants: [
              { id: "mh_thar_d_mt_4x4", name: "LX 2.2 Diesel MT 4x4", engineId: "e_mh_mhawk130", transmissionId: "t_mt6", bodyType: "SUV", drivetrain: "4WD" },
              { id: "mh_thar_p_at_4x4", name: "LX 2.0 Petrol AT 4x4", engineId: "e_mh_mstallion150", transmissionId: "t_at6", bodyType: "SUV", drivetrain: "4WD" },
              { id: "mh_thar_d_mt_rwd", name: "LX 1.5 Diesel MT RWD", engineId: "e_mh_mhawk115", transmissionId: "t_mt6", bodyType: "SUV", drivetrain: "RWD" }
            ]
          }
        ]
      }
    ]
  },
  {
    id: "vw",
    name: "Volkswagen",
    models: [
      {
        id: "vw_virtus", name: "Virtus", isCurrent: true,
        generations: [
          {
            id: "vw_virtus_gen1", name: "Gen 1", startYear: 2022, endYear: null,
            variants: [
              { id: "vw_virtus_10_mt", name: "1.0 TSI Highline MT", engineId: "e_vw_10tsi", transmissionId: "t_mt6", bodyType: "SEDAN", drivetrain: "FWD" },
              { id: "vw_virtus_10_at", name: "1.0 TSI Topline AT", engineId: "e_vw_10tsi", transmissionId: "t_at6", bodyType: "SEDAN", drivetrain: "FWD" },
              { id: "vw_virtus_15_dct", name: "1.5 TSI GT Plus DSG", engineId: "e_vw_15tsi", transmissionId: "t_dsg7", bodyType: "SEDAN", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "vw_taigun", name: "Taigun", isCurrent: true,
        generations: [
          {
            id: "vw_taigun_gen1", name: "Gen 1", startYear: 2021, endYear: null,
            variants: [
              { id: "vw_taigun_10_mt", name: "1.0 TSI Highline MT", engineId: "e_vw_10tsi", transmissionId: "t_mt6", bodyType: "COMPACT_SUV", drivetrain: "FWD" },
              { id: "vw_taigun_10_at", name: "1.0 TSI Topline AT", engineId: "e_vw_10tsi", transmissionId: "t_at6", bodyType: "COMPACT_SUV", drivetrain: "FWD" },
              { id: "vw_taigun_15_dct", name: "1.5 TSI GT Plus DSG", engineId: "e_vw_15tsi", transmissionId: "t_dsg7", bodyType: "COMPACT_SUV", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "vw_polo", name: "Polo", isCurrent: false,
        generations: [
          {
            id: "vw_polo_mk5", name: "Mk5 (India)", startYear: 2010, endYear: 2022,
            variants: [
              { id: "vw_polo_12_mt", name: "1.2 MPI Highline MT", engineId: "e_vw_12tsi", transmissionId: "t_mt5", bodyType: "HATCHBACK", drivetrain: "FWD" },
              { id: "vw_polo_15d_mt", name: "1.5 TDI Highline MT", engineId: "e_vw_15tdi", transmissionId: "t_mt5", bodyType: "HATCHBACK", drivetrain: "FWD" },
              { id: "vw_polo_12_dsg", name: "1.2 TSI GT DSG", engineId: "e_vw_12tsi", transmissionId: "t_dsg7", bodyType: "HATCHBACK", drivetrain: "FWD" },
              { id: "vw_polo_10_mt", name: "1.0 TSI Highline Plus MT", engineId: "e_vw_10tsi", transmissionId: "t_mt6", bodyType: "HATCHBACK", drivetrain: "FWD" },
              { id: "vw_polo_10_at", name: "1.0 TSI Highline Plus AT", engineId: "e_vw_10tsi", transmissionId: "t_at6", bodyType: "HATCHBACK", drivetrain: "FWD" }
            ]
          }
        ]
      }
    ]
  },
  {
    id: "skoda",
    name: "Škoda",
    models: [
      {
        id: "skoda_slavia", name: "Slavia", isCurrent: true,
        generations: [
          {
            id: "skoda_slavia_gen1", name: "Gen 1", startYear: 2022, endYear: null,
            variants: [
              { id: "skoda_slavia_10_mt", name: "1.0 TSI Ambition MT", engineId: "e_vw_10tsi", transmissionId: "t_mt6", bodyType: "SEDAN", drivetrain: "FWD" },
              { id: "skoda_slavia_10_at", name: "1.0 TSI Style AT", engineId: "e_vw_10tsi", transmissionId: "t_at6", bodyType: "SEDAN", drivetrain: "FWD" },
              { id: "skoda_slavia_15_dct", name: "1.5 TSI Style DSG", engineId: "e_vw_15tsi", transmissionId: "t_dsg7", bodyType: "SEDAN", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "skoda_kushaq", name: "Kushaq", isCurrent: true,
        generations: [
          {
            id: "skoda_kushaq_gen1", name: "Gen 1", startYear: 2021, endYear: null,
            variants: [
              { id: "skoda_kushaq_10_mt", name: "1.0 TSI Ambition MT", engineId: "e_vw_10tsi", transmissionId: "t_mt6", bodyType: "COMPACT_SUV", drivetrain: "FWD" },
              { id: "skoda_kushaq_10_at", name: "1.0 TSI Style AT", engineId: "e_vw_10tsi", transmissionId: "t_at6", bodyType: "COMPACT_SUV", drivetrain: "FWD" },
              { id: "skoda_kushaq_15_dct", name: "1.5 TSI Style DSG", engineId: "e_vw_15tsi", transmissionId: "t_dsg7", bodyType: "COMPACT_SUV", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "skoda_kylaq", name: "Kylaq", isCurrent: true,
        generations: [
          {
            id: "skoda_kylaq_gen1", name: "Gen 1", startYear: 2025, endYear: null,
            variants: [
              { id: "skoda_kylaq_10_mt", name: "1.0 TSI Ambition MT", engineId: "e_vw_10tsi", transmissionId: "t_mt6", bodyType: "COMPACT_SUV", drivetrain: "FWD" },
              { id: "skoda_kylaq_10_at", name: "1.0 TSI Style AT", engineId: "e_vw_10tsi", transmissionId: "t_at6", bodyType: "COMPACT_SUV", drivetrain: "FWD" }
            ]
          }
        ]
      }
    ]
  },
  {
    id: "toyota",
    name: "Toyota",
    models: [
      {
        id: "toyota_innova_crysta", name: "Innova Crysta", isCurrent: true,
        generations: [
          {
            id: "toyota_innova_crysta_gen1", name: "Gen 2 (Crysta)", startYear: 2016, endYear: null,
            variants: [
              { id: "toyota_innova_crysta_24d_mt", name: "2.4 ZX Diesel MT", engineId: "e_toyota_24diesel", transmissionId: "t_mt5", bodyType: "MPV", drivetrain: "RWD" }
            ]
          }
        ]
      },
      {
        id: "toyota_innova_hycross", name: "Innova Hycross", isCurrent: true,
        generations: [
          {
            id: "toyota_innova_hycross_gen1", name: "Gen 3 (Hycross)", startYear: 2023, endYear: null,
            variants: [
              { id: "toyota_innova_hycross_hybrid_zx", name: "ZX Hybrid", engineId: "e_toyota_15hybrid", transmissionId: "t_ecvt", bodyType: "MPV", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "toyota_fortuner", name: "Fortuner", isCurrent: true,
        generations: [
          {
            id: "toyota_fortuner_gen2", name: "Gen 2", startYear: 2016, endYear: null,
            variants: [
              { id: "toyota_fortuner_28d_at_4x4", name: "2.8 Diesel AT 4x4", engineId: "e_toyota_28diesel", transmissionId: "t_at6", bodyType: "SUV", drivetrain: "4WD" },
              { id: "toyota_fortuner_27p_at", name: "2.7 Petrol AT", engineId: "e_toyota_27petrol", transmissionId: "t_at6", bodyType: "SUV", drivetrain: "RWD" }
            ]
          }
        ]
      }
    ]
  },
  {
    id: "honda",
    name: "Honda",
    models: [
      {
        id: "honda_city", name: "City", isCurrent: true,
        generations: [
          {
            id: "honda_city_gen5", name: "Gen 5", startYear: 2020, endYear: null,
            variants: [
              { id: "honda_city_15p_mt", name: "1.5 ZX MT", engineId: "e_honda_15ivtec", transmissionId: "t_mt6", bodyType: "SEDAN", drivetrain: "FWD" },
              { id: "honda_city_15p_cvt", name: "1.5 ZX CVT", engineId: "e_honda_15ivtec", transmissionId: "t_cvt", bodyType: "SEDAN", drivetrain: "FWD" },
              { id: "honda_city_ehev", name: "e:HEV ZX", engineId: "e_honda_15hybrid", transmissionId: "t_ecvt", bodyType: "SEDAN", drivetrain: "FWD" }
            ]
          },
          {
            id: "honda_city_gen4", name: "Gen 4", startYear: 2014, endYear: 2023,
            variants: [
              { id: "honda_city_15p_mt_gen4", name: "1.5 V MT", engineId: "e_honda_15ivtec", transmissionId: "t_mt5", bodyType: "SEDAN", drivetrain: "FWD" },
              { id: "honda_city_15d_mt_gen4", name: "1.5 VX MT Diesel", engineId: "e_honda_15idtec", transmissionId: "t_mt6", bodyType: "SEDAN", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "honda_amaze", name: "Amaze", isCurrent: true,
        generations: [
          {
            id: "honda_amaze_gen2", name: "Gen 2", startYear: 2018, endYear: null,
            variants: [
              { id: "honda_amaze_12p_mt", name: "1.2 VX MT", engineId: "e_honda_12ivtec", transmissionId: "t_mt5", bodyType: "SEDAN", drivetrain: "FWD" },
              { id: "honda_amaze_12p_cvt", name: "1.2 VX CVT", engineId: "e_honda_12ivtec", transmissionId: "t_cvt", bodyType: "SEDAN", drivetrain: "FWD" }
            ]
          }
        ]
      }
    ]
  },
  {
    id: "renault",
    name: "Renault",
    models: [
      {
        id: "rn_kiger", name: "Kiger", isCurrent: true,
        generations: [
          {
            id: "rn_kiger_gen1", name: "Gen 1", startYear: 2021, endYear: null,
            variants: [
              { id: "rn_kiger_10_mt", name: "1.0 Energy MT", engineId: "e_rn_10na", transmissionId: "t_mt5", bodyType: "COMPACT_SUV", drivetrain: "FWD" },
              { id: "rn_kiger_10t_cvt", name: "1.0 Turbo CVT", engineId: "e_rn_10turbo", transmissionId: "t_cvt", bodyType: "COMPACT_SUV", drivetrain: "FWD" }
            ]
          }
        ]
      },
      {
        id: "rn_duster", name: "Duster", isCurrent: false,
        generations: [
          {
            id: "rn_duster_gen1", name: "Gen 1 (India)", startYear: 2012, endYear: 2022,
            variants: [
              { id: "rn_duster_15d_85", name: "1.5 dCi 85 PS MT", engineId: "e_rn_15dci", transmissionId: "t_mt5", bodyType: "SUV", drivetrain: "FWD" },
              { id: "rn_duster_15d_110_awd", name: "1.5 dCi 110 PS AWD", engineId: "e_rn_15dci", transmissionId: "t_mt6", bodyType: "SUV", drivetrain: "AWD" },
              { id: "rn_duster_13t_cvt", name: "1.3 Turbo CVT", engineId: "e_rn_13turbo", transmissionId: "t_cvt", bodyType: "SUV", drivetrain: "FWD" }
            ]
          }
        ]
      }
    ]
  },
  {
    id: "nissan",
    name: "Nissan",
    models: [
      {
        id: "ns_magnite", name: "Magnite", isCurrent: true,
        generations: [
          {
            id: "ns_magnite_gen1", name: "Gen 1", startYear: 2020, endYear: null,
            variants: [
              { id: "ns_magnite_10_mt", name: "1.0 XL MT", engineId: "e_rn_10na", transmissionId: "t_mt5", bodyType: "COMPACT_SUV", drivetrain: "FWD" },
              { id: "ns_magnite_10t_cvt", name: "1.0 Turbo XV Premium CVT", engineId: "e_rn_10turbo", transmissionId: "t_cvt", bodyType: "COMPACT_SUV", drivetrain: "FWD" }
            ]
          }
        ]
      }
    ]
  },
  {
    id: "mg",
    name: "MG Motor",
    models: [
      {
        id: "mg_hector", name: "Hector", isCurrent: true,
        generations: [
          {
            id: "mg_hector_gen1", name: "Gen 1", startYear: 2019, endYear: null,
            variants: [
              { id: "mg_hector_15t_cvt", name: "1.5 Turbo CVT", engineId: "e_mg_15turbo", transmissionId: "t_cvt", bodyType: "SUV", drivetrain: "FWD" },
              { id: "mg_hector_20d_mt", name: "2.0 Diesel MT", engineId: "e_mg_20diesel", transmissionId: "t_mt6", bodyType: "SUV", drivetrain: "FWD" }
            ]
          }
        ]
      }
    ]
  },
  {
    id: "jeep",
    name: "Jeep",
    models: [
      {
        id: "jeep_compass", name: "Compass", isCurrent: true,
        generations: [
          {
            id: "jeep_compass_gen2", name: "Gen 2", startYear: 2017, endYear: null,
            variants: [
              { id: "jeep_compass_20d_mt", name: "2.0D Limited MT", engineId: "e_jeep_20diesel", transmissionId: "t_mt6", bodyType: "SUV", drivetrain: "FWD" },
              { id: "jeep_compass_20d_at_4x4", name: "2.0D Model S AT 4x4", engineId: "e_jeep_20diesel", transmissionId: "t_at9", bodyType: "SUV", drivetrain: "4WD" }
            ]
          }
        ]
      }
    ]
  }
];

const catalog = {
  schemaVersion: 1,
  catalogVersion: "IN-2026-V1",
  market: "IN",
  country: "India",
  engines: engines,
  transmissions: transmissions,
  manufacturers: manufacturers
};

fs.writeFileSync('app/src/main/assets/vehicle_catalog_india_v1.json', JSON.stringify(catalog, null, 2));
